/**
 * Discogs State Module
 * Manages Discogs lookup queue and batch processing
 */

import { primaryArtist, normalizeForSearch } from "../common/playlist-utils.js";
import { buildTrackKey, getRegistryEntry } from "./track-registry.js";

const DISCOGS_BATCH_SIZE = 3;
const DISCOGS_BATCH_DELAY_MS = 250;
const DISCOGS_CACHE_BATCH_DELAY_MS = 40;

export const discogsState = {
    queue: [],
    requested: new Set(),
    completed: new Set(),
    processing: false,
    lookupAliases: new Map(),
    lookupResults: new Map(),
    lookupInFlight: new Set(),
    lookupKeyByTrackKey: new Map(),
};

export function resetDiscogsState() {
    discogsState.queue.length = 0;
    discogsState.requested.clear();
    discogsState.completed.clear();
    discogsState.processing = false;
    discogsState.lookupAliases.clear();
    discogsState.lookupResults.clear();
    discogsState.lookupInFlight.clear();
    discogsState.lookupKeyByTrackKey.clear();
}

export function safeDiscogsUrl(url) {
    try {
        const parsed = new URL(url, window.location.origin);
        const host = parsed.hostname.toLowerCase();
        if ((parsed.protocol === "https:" || parsed.protocol === "http:") && host.endsWith("discogs.com")) {
            return parsed.href;
        }
        return null;
    } catch (_a) {
        return null;
    }
}

export function safeDiscogsImage(url) {
    return safeDiscogsUrl(url);
}

function isSearchFallbackUrl(url) {
    return typeof url === "string" && url.toLowerCase().includes("/search");
}

export function normalizeDiscogsMatch(value) {
    const raw = value && typeof value === "object" ? value : null;
    const candidateUrl = typeof value === "string" ? value : raw?.url;
    const url = typeof candidateUrl === "string" ? safeDiscogsUrl(candidateUrl) : null;
    if (!url) return null;
    const matchType = typeof raw?.matchType === "string" ? raw.matchType : "MANUAL_REVIEW";
    const confidence = typeof raw?.confidence === "string" ? raw.confidence : "LOW";
    const source = typeof raw?.source === "string" ? raw.source : "LEGACY_CLIENT_DATA";
    const reason = typeof raw?.reason === "string" && raw.reason.trim()
        ? raw.reason.trim()
        : "No server-side match evidence is available; verify this entry on Discogs.";
    return {
        url,
        matchType,
        confidence,
        source,
        reason,
        vinylFormatConfirmed: raw?.vinylFormatConfirmed === true,
    };
}

function normalizeLookupPart(value) {
    if (value === null || value === undefined) {
        return "";
    }
    return String(value).trim().toLowerCase();
}
function buildLookupKey(track) {
    const artist = normalizeLookupPart(normalizeForSearch(primaryArtist(track?.artist)));
    const album = normalizeLookupPart(normalizeForSearch(track?.album));
    if (!artist || !album) {
        return null;
    }
    const year = (typeof track?.releaseYear === "number" && Number.isFinite(track.releaseYear))
        ? String(track.releaseYear)
        : "";
    const barcode = normalizeLookupPart(track?.barcode);
    return [artist, album, year, barcode].join("|");
}
function addLookupAlias(lookupKey, key, index) {
    if (!lookupKey || !key) {
        return;
    }
    const aliases = discogsState.lookupAliases.get(lookupKey) ?? [];
    if (!aliases.some((item) => item.key === key)) {
        aliases.push({ key, index });
        discogsState.lookupAliases.set(lookupKey, aliases);
    }
}
function resolveLookupKey(key, explicitLookupKey) {
    if (typeof explicitLookupKey === "string" && explicitLookupKey) {
        return explicitLookupKey;
    }
    if (key && discogsState.lookupKeyByTrackKey.has(key)) {
        return discogsState.lookupKeyByTrackKey.get(key) ?? null;
    }
    return null;
}
function applyDiscogsResultToTrack(key, index, match, state, scheduleLibraryRefresh) {
    const url = match?.url ?? null;
    if (key) {
        discogsState.requested.delete(key);
        discogsState.completed.add(key);
        discogsState.lookupKeyByTrackKey.delete(key);
    }
    if (index !== null && index !== undefined && Array.isArray(state.aggregated?.tracks)) {
        const track = state.aggregated.tracks[index];
        if (track) {
            track.discogsAlbumUrl = url;
            track.discogsMatch = match;
            track.discogsStatus = url ? "found" : "not-found";
        }
    }
    const entry = key ? getRegistryEntry(key) : undefined;
    const targetEntry = entry ?? (index !== null && index !== undefined
        ? getRegistryEntry(buildTrackKey(state.aggregated?.tracks?.[index], index))
        : undefined);
    if (targetEntry?.setDiscogsState) {
        targetEntry.setDiscogsState(url ? "found" : "not-found", match ?? undefined);
    }
    if (url) {
        scheduleLibraryRefresh(200);
    }
}
export function applyDiscogsResult(result, state, scheduleLibraryRefresh) {
    if (!result) return;
    const key = typeof result.key === "string" ? result.key : null;
    const index = typeof result.index === "number" ? result.index : null;
    const match = normalizeDiscogsMatch(result);
    const url = match?.url ?? null;
    const lookupKey = resolveLookupKey(key, result.lookupKey);
    const aliases = lookupKey ? discogsState.lookupAliases.get(lookupKey) : null;
    if (Array.isArray(aliases) && aliases.length > 0) {
        for (const alias of aliases) {
            const aliasKey = typeof alias?.key === "string" ? alias.key : null;
            const aliasIndex = typeof alias?.index === "number" ? alias.index : null;
            applyDiscogsResultToTrack(aliasKey, aliasIndex, match, state, scheduleLibraryRefresh);
        }
    }
    else {
        applyDiscogsResultToTrack(key, index, match, state, scheduleLibraryRefresh);
    }
    if (lookupKey) {
        discogsState.lookupResults.set(lookupKey, match);
        discogsState.lookupInFlight.delete(lookupKey);
        discogsState.lookupAliases.delete(lookupKey);
    }
}

export async function processDiscogsQueue(state, scheduleLibraryRefresh) {
    if (discogsState.processing) {
        return;
    }
    discogsState.processing = true;
    
    try {
        while (discogsState.queue.length) {
            const batch = [];
            while (discogsState.queue.length && batch.length < DISCOGS_BATCH_SIZE) {
                const candidate = discogsState.queue.shift();
                if (!candidate) {
                    continue;
                }
                if (candidate.key && discogsState.completed.has(candidate.key)) {
                    continue;
                }
                batch.push(candidate);
            }
            
            if (!batch.length) {
                continue;
            }
            const payload = {
                tracks: batch.map((item) => ({
                    key: item.key,
                    index: item.index,
                    lookupKey: item.lookupKey ?? null,
                    artist: primaryArtist(item.track?.artist) || null,
                    album: normalizeForSearch(item.track?.album) || null,
                    releaseYear: item.track?.releaseYear ?? null,
                    track: normalizeForSearch(item.track?.trackName) || null,
                    barcode: item.track?.barcode ?? null,
                })),
            };
            let cacheOnlyBatch = false;
            try {
                const response = await fetch("/api/discogs/batch", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(payload),
                    credentials: "include",
                });
                
                if (!response.ok) {
                    throw new Error(`HTTP ${response.status}`);
                }
                
                const data = await response.json();
                if (Array.isArray(data?.results)) {
                    cacheOnlyBatch = data.results.length > 0 && data.results.every((result) => result?.cacheHit === true);
                    for (const result of data.results) {
                        applyDiscogsResult(result, state, scheduleLibraryRefresh);
                    }
                }
            } catch (error) {
                console.warn("Discogs-Batch fehlgeschlagen:", error);
                for (const item of batch) {
                    if (item?.key) {
                        discogsState.requested.delete(item.key);
                        discogsState.lookupKeyByTrackKey.delete(item.key);
                    }
                    if (item?.lookupKey) {
                        const aliases = discogsState.lookupAliases.get(item.lookupKey);
                        if (Array.isArray(aliases)) {
                            for (const alias of aliases) {
                                if (alias?.key) {
                                    discogsState.requested.delete(alias.key);
                                    discogsState.lookupKeyByTrackKey.delete(alias.key);
                                }
                            }
                        }
                        discogsState.lookupInFlight.delete(item.lookupKey);
                        discogsState.lookupAliases.delete(item.lookupKey);
                    }
                }
            }
            if (discogsState.queue.length) {
                const delay = cacheOnlyBatch ? DISCOGS_CACHE_BATCH_DELAY_MS : DISCOGS_BATCH_DELAY_MS;
                await new Promise((resolve) => setTimeout(resolve, delay));
            }
        }
    } finally {
        discogsState.processing = false;
    }
}

export function queueDiscogsLookups(startIndex, tracks, state, scheduleLibraryRefresh) {
    if (!Array.isArray(tracks)) {
        return;
    }
    
    for (let i = 0; i < tracks.length; i++) {
        const track = tracks[i];
        if (!track) {
            continue;
        }
        
        const index = (typeof startIndex === "number" ? startIndex : 0) + i;
        const key = buildTrackKey(track, index);
        if (!key) {
            continue;
        }
        const lookupKey = buildLookupKey(track);
        if (lookupKey) {
            discogsState.lookupKeyByTrackKey.set(key, lookupKey);
            addLookupAlias(lookupKey, key, index);
        }
        
        const existingUrl = typeof track.discogsAlbumUrl === "string"
            ? safeDiscogsUrl(track.discogsAlbumUrl)
            : null;
        track.discogsAlbumUrl = existingUrl;
        const existingMatch = normalizeDiscogsMatch(track.discogsMatch);

        if (existingMatch && existingMatch.url === existingUrl && !isSearchFallbackUrl(existingUrl)) {
            discogsState.completed.add(key);
            if (lookupKey) {
                discogsState.lookupResults.set(lookupKey, existingMatch);
                discogsState.lookupInFlight.delete(lookupKey);
                discogsState.lookupAliases.delete(lookupKey);
            }
            applyDiscogsResult({ key, index, ...existingMatch, lookupKey }, state, scheduleLibraryRefresh);
            continue;
        }
        track.discogsStatus = "pending";
        if (lookupKey && discogsState.lookupResults.has(lookupKey)) {
            applyDiscogsResult({ key, index, ...discogsState.lookupResults.get(lookupKey), lookupKey }, state, scheduleLibraryRefresh);
            continue;
        }
        if (discogsState.completed.has(key) || discogsState.requested.has(key)) {
            continue;
        }
        discogsState.requested.add(key);
        if (lookupKey && discogsState.lookupInFlight.has(lookupKey)) {
            continue;
        }
        if (lookupKey) {
            discogsState.lookupInFlight.add(lookupKey);
        }
        discogsState.queue.push({ key, index, track, lookupKey });
    }
    return processDiscogsQueue(state, scheduleLibraryRefresh);
}

export function markDiscogsResult(key, index, match, state, scheduleLibraryRefresh) {
    const lookupKey = key ? discogsState.lookupKeyByTrackKey.get(key) : null;
    const normalized = normalizeDiscogsMatch(match);
    applyDiscogsResult({ key, index, ...(normalized ?? {}), lookupKey }, state, scheduleLibraryRefresh);
}

/**
 * Track Renderer Module
 * Creates track elements and handles quality badges
 */

import { primaryArtist, normalizeForSearch } from "../common/playlist-utils.js";
import { getVendors, buildVendorUrl } from "../common/vendors.js";
import { buildTrackKey, registerTrackElement } from "./track-registry.js";
import { safeDiscogsUrl, markDiscogsResult, discogsState, normalizeDiscogsMatch } from "./discogs-state.js";
import { discogsUiState, rememberLibraryState, scheduleLibraryRefresh } from "./discogs-ui.js";

function emitPlaylistStatus(message, tone = "neutral") {
    try {
        window.dispatchEvent(new CustomEvent("vm:playlist-status", { detail: { message, tone } }));
    }
    catch (_a) {
    }
}

async function checkWantlistMembership(targetUrl) {
    const safeUrl = safeDiscogsUrl(targetUrl);
    if (!safeUrl) {
        return null;
    }
    try {
        const res = await fetch("/api/discogs/library-status", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ urls: [safeUrl] }),
            credentials: "include",
        });
        if (!res.ok) {
            return null;
        }
        const payload = await res.json();
        const results = Array.isArray(payload?.results) ? payload.results : [];
        const result = results.find((item) => {
            const entryUrl = typeof item?.url === "string" ? safeDiscogsUrl(item.url) : null;
            return entryUrl === safeUrl;
        });
        if (!result) {
            return false;
        }
        return !!result.inWishlist || !!result.inCollection;
    } catch (_a) {
        return null;
    }
}

const PLACEHOLDER_IMG = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";
const ICON_DISCOGS = `<img class="vm-action-icon vm-action-icon--light" src="/design/discogs_trans_black.svg" alt="" aria-hidden="true"><img class="vm-action-icon vm-action-icon--dark" src="/design/discogs_trans_white.svg" alt="" aria-hidden="true">`;
const ICON_WANT = `<img class="vm-action-icon vm-action-icon--light" src="/design/heart_trans_black.svg" alt="" aria-hidden="true"><img class="vm-action-icon vm-action-icon--dark" src="/design/heart_trans_white.svg" alt="" aria-hidden="true">`;
const ICON_LIBRARY_WISHLIST = `<img class="vm-library-icon vm-library-icon--light" src="/design/heart_trans_black.svg" alt="" aria-hidden="true"><img class="vm-library-icon vm-library-icon--dark" src="/design/heart_trans_white.svg" alt="" aria-hidden="true">`;
const ICON_LIBRARY_OWNED = `<img class="vm-library-icon vm-library-icon--light" src="/design/collection_trans_black.svg" alt="" aria-hidden="true"><img class="vm-library-icon vm-library-icon--dark" src="/design/collection_trans_white.svg" alt="" aria-hidden="true">`;
const DEFAULT_VENDOR_ICONS = {
    hhv: { light: "/design/hhv_trans_black.svg", dark: "/design/hhv_trans_white.svg" },
    jpc: { light: "/design/jpc_trans_black.svg", dark: "/design/jpc_trans_white.svg" },
    amazon: { light: "/design/amazon_trans_black.svg", dark: "/design/amazon_trans_white.svg" },
};

let matchEvidenceIdCounter = 0;

function resolveVendorIcons(vendor) {
    if (!vendor) return null;
    const fallback = DEFAULT_VENDOR_ICONS[vendor.id] || null;
    const light = typeof vendor.iconLight === "string"
        ? vendor.iconLight
        : (typeof vendor?.icon?.light === "string"
            ? vendor.icon.light
            : fallback?.light);
    const dark = typeof vendor.iconDark === "string"
        ? vendor.iconDark
        : (typeof vendor?.icon?.dark === "string"
            ? vendor.icon.dark
            : fallback?.dark);
    if (!light || !dark) return null;
    return { light, dark };
}

function appendVendorIcons(link, vendor, fallback) {
    const icons = resolveVendorIcons(vendor);
    if (!icons || !fallback) return;
    const light = document.createElement("img");
    light.className = "vm-action-icon vm-action-icon--light";
    light.src = icons.light;
    light.alt = "";
    light.setAttribute("aria-hidden", "true");
    const dark = document.createElement("img");
    dark.className = "vm-action-icon vm-action-icon--dark";
    dark.src = icons.dark;
    dark.alt = "";
    dark.setAttribute("aria-hidden", "true");
    const fallbackToLetter = () => {
        link.classList.remove("vendor-link--has-icon");
        light.remove();
        dark.remove();
    };
    light.addEventListener("error", fallbackToLetter, { once: true });
    dark.addEventListener("error", fallbackToLetter, { once: true });
    link.classList.add("vendor-link--has-icon");
    link.appendChild(light);
    link.appendChild(dark);
}

export function determineMatchQuality(match) {
    if (!match || typeof match !== "object") {
        return { level: "poor", label: "Search now" };
    }
    switch (match.matchType) {
        case "EXACT_RELEASE":
            return { level: "good", label: "Release match" };
        case "EXACT_MASTER":
            return { level: "medium", label: "Master match" };
        case "LIKELY_MATCH":
            return { level: "medium", label: "Likely match" };
        case "CURATED_MATCH":
            return { level: "good", label: "Curated match" };
        case "SEARCH_ONLY":
            return { level: "poor", label: "Search results" };
        default:
            return { level: "poor", label: "Verify release" };
    }
}

export function createQualityBadge(quality) {
    const badge = document.createElement("span");
    badge.className = `match-quality match-quality--${quality.level}`;
    badge.textContent = quality.label;
    return badge;
}

function formatMatchSource(match) {
    switch (match?.source) {
        case "BARCODE": return "Barcode";
        case "MANUAL_CURATION": return "Manual curation";
        case "DISCOGS_CATALOG": return "Discogs catalog";
        case "DISCOGS_SEARCH": return "Discogs search";
        case "LEGACY_CACHE": return "Legacy cache";
        default:
            return ["EXACT_RELEASE", "EXACT_MASTER", "LIKELY_MATCH"].includes(match?.matchType)
                ? "Discogs match"
                : "Unverified source";
    }
}

export function buildVendorLinks(track) {
    const vendors = getVendors();
    return vendors.map((vendor) => {
        const url = buildVendorUrl(vendor, track);
        const b = document.createElement("a");
        const vendorName = vendor.name || vendor.id || "Vendor";
        const fallbackLabel = document.createElement("span");
        fallbackLabel.className = "vm-action-fallback";
        fallbackLabel.textContent = vendor.label || vendor.id?.charAt(0)?.toUpperCase() || "?";
        b.appendChild(fallbackLabel);
        appendVendorIcons(b, vendor, fallbackLabel);
        b.title = vendorName;
        b.setAttribute("aria-label", vendorName);
        b.classList.add("vendor-link");
        if (vendor.id) {
            b.classList.add(`vendor-link--${String(vendor.id).toLowerCase()}`);
        }

        if (url) {
            b.href = url;
            b.target = "_blank";
            b.rel = "noopener noreferrer";
            b.classList.remove("inactive");
            b.setAttribute("aria-disabled", "false");
        } else {
            b.href = "#";
            b.classList.add("inactive");
            b.setAttribute("aria-disabled", "true");
        }
        return b;
    });
}

export function createTrackElement(track, index, state) {
    const key = buildTrackKey(track, index);
    const initialDiscogsUrl = safeDiscogsUrl(track.discogsAlbumUrl);
    track.discogsAlbumUrl = initialDiscogsUrl;
    const initialDiscogsMatch = normalizeDiscogsMatch(track.discogsMatch);
    track.discogsMatch = initialDiscogsMatch;

    const initialState = initialDiscogsUrl
        ? "found"
        : (track.discogsStatus === "not-found" ? "not-found" : "pending");

    const initialQuality = initialDiscogsUrl
        ? determineMatchQuality(initialDiscogsMatch)
        : (initialState === "not-found"
            ? { level: "poor", label: "Search now" }
            : { level: "pending", label: "Checking Discogs" });

    const trackDiv = document.createElement("div");
    trackDiv.className = "track";
    if (key) trackDiv.dataset.trackKey = key;
    trackDiv.dataset.matchQuality = initialQuality.level;

    const img = document.createElement("img");
    img.className = "cover";
    img.src = track.coverUrl || PLACEHOLDER_IMG;
    img.alt = track.album ? `Album cover for ${track.album}` : "Album cover";

    const infoDiv = document.createElement("div");
    infoDiv.className = "info";

    const titleDiv = document.createElement("div");
    titleDiv.className = "track-title";
    titleDiv.textContent = track.trackName || "Unknown track";

    const albumDiv = document.createElement("div");
    albumDiv.className = "album";
    if (track.albumUrl) {
        const a = document.createElement("a");
        a.href = track.albumUrl;
        a.target = "_blank";
        a.rel = "noopener noreferrer";
        a.textContent = track.album;
        albumDiv.appendChild(a);
    } else {
        albumDiv.textContent = track.album;
    }

    const artistsDiv = document.createElement("div");
    artistsDiv.className = "artists";
    artistsDiv.textContent = track.artist;

    const metaRow = document.createElement("div");
    metaRow.className = "meta-row";

    const qualityBadge = createQualityBadge(initialQuality);
    metaRow.appendChild(qualityBadge);

    const matchEvidenceId = `match-evidence-${matchEvidenceIdCounter++}`;
    const matchInfoToggle = document.createElement("button");
    matchInfoToggle.type = "button";
    matchInfoToggle.className = "match-evidence-toggle hidden";
    matchInfoToggle.setAttribute("aria-controls", matchEvidenceId);
    matchInfoToggle.setAttribute("aria-expanded", "false");
    matchInfoToggle.setAttribute("aria-label", "Show match details");
    matchInfoToggle.innerHTML = '<span class="match-evidence-toggle__icon" aria-hidden="true">i</span><span class="sr-only">Show match details</span>';
    metaRow.appendChild(matchInfoToggle);

    const matchEvidence = document.createElement("div");
    matchEvidence.id = matchEvidenceId;
    matchEvidence.className = "match-evidence hidden";
    matchEvidence.setAttribute("role", "region");
    matchEvidence.setAttribute("aria-labelledby", matchEvidenceId + "-toggle");
    matchInfoToggle.id = matchEvidenceId + "-toggle";

    const libraryBadge = document.createElement("span");
    libraryBadge.className = "discogs-library hidden";
    libraryBadge.setAttribute("role", "img");
    metaRow.appendChild(libraryBadge);

    if (typeof track.releaseYear === "number") {
        const year = document.createElement("span");
        year.className = "release-year";
        year.textContent = String(track.releaseYear);
        metaRow.appendChild(year);
    }
    infoDiv.appendChild(titleDiv);
    infoDiv.appendChild(artistsDiv);
    infoDiv.appendChild(albumDiv);
    infoDiv.appendChild(metaRow);
    infoDiv.appendChild(matchEvidence);

    const actions = document.createElement("div");
    actions.className = "actions";

    const discogsBtn = document.createElement("a");
    discogsBtn.href = "#";
    discogsBtn.classList.add("discogs-action");
    discogsBtn.setAttribute("aria-label", "Search Discogs now");
    discogsBtn.innerHTML = ICON_DISCOGS;
    actions.appendChild(discogsBtn);

    const wishlistBtn = document.createElement("a");
    wishlistBtn.href = "#";
    wishlistBtn.classList.add("discogs-action");
    wishlistBtn.setAttribute("aria-label", "Add to Discogs wantlist");
    wishlistBtn.innerHTML = ICON_WANT;
    actions.appendChild(wishlistBtn);

    let currentLibraryState = null;

    const setMatchEvidenceOpen = (open) => {
        const expanded = open === true;
        matchInfoToggle.setAttribute("aria-expanded", expanded ? "true" : "false");
        matchEvidence.classList.toggle("hidden", !expanded);
        matchInfoToggle.setAttribute("aria-label", expanded ? "Hide match details" : "Show match details");
        const label = matchInfoToggle.querySelector(".sr-only");
        if (label) {
            label.textContent = expanded ? "Hide match details" : "Show match details";
        }
    };

    matchInfoToggle.addEventListener("click", () => {
        setMatchEvidenceOpen(matchInfoToggle.getAttribute("aria-expanded") !== "true");
    });

    const hasDirectDiscogsTarget = (match) => {
        return match?.matchType === "EXACT_RELEASE";
    };

    const describeDiscogsState = (discogsStateVal, matchValue) => {
        const match = normalizeDiscogsMatch(matchValue);
        const safeUrl = match?.url ?? null;
        if (discogsStateVal === "found" && safeUrl) {
            const quality = determineMatchQuality(match);
            return {
                quality,
                badgeTitle: `Match quality: ${quality.label}`,
                buttonTitle: match.matchType === "SEARCH_ONLY" ? "Open Discogs search results" : "Open on Discogs",
                buttonLabel: match.matchType === "SEARCH_ONLY" ? "Open Discogs search results" : "Open on Discogs",
            };
        }
        if (discogsStateVal === "pending") {
            return {
                quality: { level: "pending", label: "Checking Discogs" },
                badgeTitle: "Discogs lookup is still running.",
                buttonTitle: "Discogs lookup in progress",
                buttonLabel: "Discogs lookup in progress",
            };
        }
        return {
            quality: { level: "poor", label: "Search now" },
            badgeTitle: "No Discogs result is saved yet. Click the Discogs button to search now.",
            buttonTitle: "Search Discogs now",
            buttonLabel: "Search Discogs now",
        };
    };

    const syncWishlistActionState = () => {
        const alreadyTracked = currentLibraryState === "wishlist" || currentLibraryState === "owned";
        const canAdd = hasDirectDiscogsTarget(track.discogsMatch)
            && track.discogsStatus === "found"
            && discogsUiState.loggedIn
            && !alreadyTracked;
        wishlistBtn.setAttribute("aria-disabled", canAdd ? "false" : "true");
        wishlistBtn.classList.toggle("inactive", !canAdd);
        if (!discogsUiState.loggedIn) {
            wishlistBtn.title = "Connect Discogs first";
        } else if (alreadyTracked) {
            wishlistBtn.title = currentLibraryState === "owned" ? "Already in collection" : "Already in wantlist";
        } else if (track.discogsStatus === "found" && !hasDirectDiscogsTarget(track.discogsMatch)) {
            wishlistBtn.title = "Verify and choose a concrete release first";
        } else {
            wishlistBtn.title = canAdd ? "Add to Discogs wantlist" : "Discogs match required";
        }
    };

    const vendorLinks = buildVendorLinks(track);
    for (const link of vendorLinks) {
        if (link.getAttribute("aria-disabled") === "true") {
            link.addEventListener("click", (ev) => {
                ev.preventDefault();
                ev.stopPropagation();
            });
        }
        actions.appendChild(link);
    }

    const updateBadge = (quality) => {
        qualityBadge.textContent = quality.label;
        qualityBadge.className = `match-quality match-quality--${quality.level}`;
        trackDiv.dataset.matchQuality = quality.level;
    };

    const setDiscogsState = (discogsStateVal, matchValue) => {
        const match = normalizeDiscogsMatch(matchValue);
        const safeUrl = match?.url ?? null;
        const presentation = describeDiscogsState(discogsStateVal, match);
        trackDiv.dataset.discogsState = discogsStateVal;
        updateBadge(presentation.quality);
        qualityBadge.title = presentation.badgeTitle;
        qualityBadge.setAttribute("aria-label", presentation.badgeTitle);
        discogsBtn.title = presentation.buttonTitle;
        discogsBtn.setAttribute("aria-label", presentation.buttonLabel);

        if (match) {
            const confidence = match.confidence.toLowerCase();
            matchEvidence.textContent = `${formatMatchSource(match)} · ${confidence} confidence · ${match.reason}`;
            matchEvidence.title = match.vinylFormatConfirmed
                ? "Discogs explicitly confirmed vinyl format metadata."
                : "Vinyl format is not confirmed; verify the selected release on Discogs.";
            matchInfoToggle.classList.remove("hidden");
            setMatchEvidenceOpen(false);
        } else {
            matchEvidence.textContent = "";
            matchEvidence.removeAttribute("title");
            matchInfoToggle.classList.add("hidden");
            setMatchEvidenceOpen(false);
        }

        if (discogsStateVal === "found" && safeUrl) {
            track.discogsAlbumUrl = safeUrl;
            track.discogsMatch = match;
            track.discogsStatus = "found";
            discogsBtn.classList.remove("inactive", "pending");
            discogsBtn.setAttribute("aria-disabled", "false");
            discogsBtn.href = safeUrl;
            discogsBtn.target = "_blank";
            discogsBtn.rel = "noopener noreferrer";
        } else if (discogsStateVal === "pending") {
            track.discogsStatus = "pending";
            discogsBtn.href = "#";
            discogsBtn.classList.add("inactive", "pending");
            discogsBtn.setAttribute("aria-disabled", "true");
            discogsBtn.removeAttribute("target");
            discogsBtn.removeAttribute("rel");
        } else {
            track.discogsAlbumUrl = null;
            track.discogsMatch = null;
            track.discogsStatus = "not-found";
            discogsBtn.href = "#";
            discogsBtn.classList.remove("pending");
            discogsBtn.classList.remove("inactive");
            discogsBtn.setAttribute("aria-disabled", "false");
            discogsBtn.removeAttribute("target");
            discogsBtn.removeAttribute("rel");
        }

        syncWishlistActionState();
    };

    const setLibraryState = (libraryState) => {
        currentLibraryState = libraryState || null;
        if (!libraryState) {
            libraryBadge.className = "discogs-library hidden";
            libraryBadge.innerHTML = "";
            libraryBadge.removeAttribute("title");
            libraryBadge.removeAttribute("aria-label");
            syncWishlistActionState();
            return;
        }
        const isOwned = libraryState === "owned";
        const label = isOwned ? "Already in Discogs collection" : "Already in Discogs wantlist";
        libraryBadge.className = `discogs-library discogs-library--icon ${libraryState}`;
        libraryBadge.innerHTML = isOwned ? ICON_LIBRARY_OWNED : ICON_LIBRARY_WISHLIST;
        libraryBadge.title = label;
        libraryBadge.setAttribute("aria-label", label);
        syncWishlistActionState();
    };

    registerTrackElement(key, { index, setDiscogsState, setLibraryState, element: trackDiv });
    setLibraryState(null);
    const onDiscogsAuthState = () => {
        syncWishlistActionState();
        if (!trackDiv.isConnected) {
            window.removeEventListener("vm:discogs-auth-state", onDiscogsAuthState);
        }
    };
    window.addEventListener("vm:discogs-auth-state", onDiscogsAuthState);

    let manualSearching = false;
    discogsBtn.addEventListener("click", async (ev) => {
        if (discogsBtn.getAttribute("aria-disabled") === "true") {
            ev.preventDefault();
            return;
        }
        if (track.discogsAlbumUrl) {
            return;
        }
        ev.preventDefault();
        if (manualSearching) {
            return;
        }
        manualSearching = true;
        setDiscogsState("pending");

        try {
            const res = await fetch("/api/discogs/search", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    artist: primaryArtist(track.artist) || track.artist,
                    album: normalizeForSearch(track.album) || track.album,
                    releaseYear: track.releaseYear ?? null,
                    track: normalizeForSearch(track.trackName) || track.trackName,
                }),
                credentials: "include",
            });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);

            const data = await res.json();
            const match = normalizeDiscogsMatch(data);
            if (match) {
                markDiscogsResult(key, index, match, state, (delay) => scheduleLibraryRefresh(delay, state));
                manualSearching = false;
                window.open(match.url, "_blank", "noopener");
                return;
            }
        } catch (error) {
            console.warn("Discogs search failed:", error);
        } finally {
            if (!track.discogsAlbumUrl) {
                setDiscogsState("not-found");
            }
            manualSearching = false;
        }
    });

    wishlistBtn.addEventListener("click", async (ev) => {
        ev.preventDefault();
        if (wishlistBtn.getAttribute("aria-disabled") === "true") {
            return;
        }
        if (!discogsUiState.loggedIn) {
            emitPlaylistStatus("Please connect Discogs first.", "error");
            return;
        }

        const targetUrl = track.discogsAlbumUrl;
        if (!targetUrl) {
            return;
        }

        wishlistBtn.setAttribute("aria-disabled", "true");
        wishlistBtn.classList.add("inactive");

        try {
            const res = await fetch("/api/discogs/wishlist/add", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ url: targetUrl }),
                credentials: "include",
            });
            if (res.status === 409) {
                const membership = await checkWantlistMembership(targetUrl);
                if (membership === true) {
                    rememberLibraryState(track.artist, track.album, "wishlist");
                    setLibraryState("wishlist");
                    scheduleLibraryRefresh(150, state);
                    return;
                }
                if (membership === false) {
                    throw new Error("Conflict: release is not in your wantlist.");
                }
                throw new Error("Conflict while adding to wantlist. Please refresh Discogs status.");
            }
            if (!res.ok) {
                let message = `HTTP ${res.status}`;
                try {
                    const payload = await res.json();
                    const apiMessage = payload?.error?.message;
                    if (typeof apiMessage === "string" && apiMessage.trim()) {
                        message = apiMessage.trim();
                    }
                } catch (_) {
                }
                throw new Error(message);
            }
            rememberLibraryState(track.artist, track.album, "wishlist");
            setLibraryState("wishlist");
            scheduleLibraryRefresh(150, state);
        } catch (e) {
            emitPlaylistStatus("Could not add to wantlist: " + (e instanceof Error ? e.message : String(e)), "error");
        } finally {
            syncWishlistActionState();
        }
    });

    trackDiv.appendChild(img);
    trackDiv.appendChild(infoDiv);
    trackDiv.appendChild(actions);

    if (initialState === "found" && track.discogsAlbumUrl) {
        setDiscogsState("found", initialDiscogsMatch ?? track.discogsAlbumUrl);
    } else if (initialState === "not-found") {
        setDiscogsState("not-found");
    } else {
        setDiscogsState("pending");
    }

    return trackDiv;
}

export { PLACEHOLDER_IMG };

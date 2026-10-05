/**
 * Playlist Page Coordinator
 * Main entry point for the playlist page - coordinates all modules
 */

import { initCurationPanel } from "./curation.js";
import { getPlaylistLoadErrorMessage, readApiError } from "./common/api-errors.js";
import { buildCurationQueue, normalizeForSearch, primaryArtist } from "./common/playlist-utils.js";
import { readCachedPlaylist, storePlaylistChunk } from "./storage.js";
import { loadCustomVendors } from "./common/vendors.js";

// Import modules
import { buildTrackKey, clearTrackRegistry, getRegistryEntry } from "./playlist/track-registry.js";
import { getStoredViewMode, applyViewMode, initViewToggle, DEFAULT_VIEW_MODE } from "./playlist/view-mode.js";
import {
    discogsState,
    resetDiscogsState,
    queueDiscogsLookups,
    markDiscogsResult,
    safeDiscogsUrl
} from "./playlist/discogs-state.js";
import {
    discogsUiState,
    setupDiscogsPanel,
    refreshLibraryBadgesLocally,
    scheduleLibraryRefresh
} from "./playlist/discogs-ui.js";
import { createTrackElement, PLACEHOLDER_IMG } from "./playlist/track-renderer.js";

// Page state
const DEFAULT_PAGE_SIZE = 20;

let state = {
    id: null,
    pageSize: DEFAULT_PAGE_SIZE,
    aggregated: null,
    prefetchedChunk: null,
    viewMode: getStoredViewMode(),
    albums: null,
    selectedAlbums: null,
    albumSelectionComplete: false,
};
let playlistStatusTimer = null;
function setupDiscogsDrawer() {
    const page = document.querySelector(".playlist-page");
    const toggle = document.getElementById("discogs-toggle");
    const backdrop = document.getElementById("discogs-backdrop");
    const sidebar = document.getElementById("discogs-sidebar");
    if (!page || !(toggle instanceof HTMLButtonElement) || !backdrop || !sidebar) {
        return;
    }
    if (toggle.dataset.bound === "true") {
        return;
    }
    toggle.dataset.bound = "true";
    const isVisibleFocusable = (node) => node instanceof HTMLElement &&
        node.offsetParent !== null &&
        !node.closest(".hidden,[aria-hidden='true']");
    const getFocusableInSidebar = () => {
        const nodes = sidebar.querySelectorAll('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
        return Array.from(nodes).filter(isVisibleFocusable);
    };
    let sidebarLastFocus = null;
    const setOpen = (open) => {
        page.classList.toggle("sidebar-open", open);
        document.body.classList.toggle("drawer-open", !!open);
        toggle.setAttribute("aria-expanded", String(!!open));
        backdrop.classList.toggle("hidden", !open);
        sidebar.setAttribute("aria-hidden", String(!open));
        if ("inert" in sidebar) {
            sidebar.inert = !open;
        }
        if (open) {
            sidebarLastFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
            getFocusableInSidebar()[0]?.focus();
        }
        else {
            sidebarLastFocus?.focus();
            sidebarLastFocus = null;
        }
    };
    toggle.addEventListener("click", () => {
        const isOpen = page.classList.contains("sidebar-open");
        setOpen(!isOpen);
    });
    backdrop.addEventListener("click", () => setOpen(false));
    window.addEventListener("keydown", (event) => {
        if (event.key === "Escape") {
            setOpen(false);
            return;
        }
        if (event.key !== "Tab" || !page.classList.contains("sidebar-open")) {
            return;
        }
        const focusable = getFocusableInSidebar();
        if (!focusable.length) {
            return;
        }
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        const active = document.activeElement;
        if (event.shiftKey && active === first) {
            event.preventDefault();
            last.focus();
        }
        else if (!event.shiftKey && active === last) {
            event.preventDefault();
            first.focus();
        }
    });
    setOpen(false);
}

// Loading overlay functions
function showOverlay(message = "Loading playlist…") {
    const overlay = document.getElementById("global-loading");
    if (!overlay) return;
    overlay.classList.remove("hidden");
    overlay.classList.add("visible");
    const text = document.getElementById("global-loading-text");
    if (text) text.textContent = message;
}

function hideOverlay() {
    const overlay = document.getElementById("global-loading");
    if (!overlay) return;
    overlay.classList.remove("visible");
    overlay.classList.add("hidden");
}
function setPlaylistStatus(message, tone = "neutral") {
    const node = document.getElementById("playlist-status");
    if (!node)
        return;
    if (playlistStatusTimer) {
        clearTimeout(playlistStatusTimer);
        playlistStatusTimer = null;
    }
    if (!message) {
        node.textContent = "";
        node.classList.add("hidden");
        node.classList.remove("error", "success");
        return;
    }
    node.textContent = message;
    node.classList.remove("hidden", "error", "success");
    if (tone === "error") {
        node.classList.add("error");
    }
    else if (tone === "success") {
        node.classList.add("success");
        playlistStatusTimer = window.setTimeout(() => {
            node.textContent = "";
            node.classList.add("hidden");
            node.classList.remove("error", "success");
            playlistStatusTimer = null;
        }, 2800);
    }
}
function registerPlaylistStatusEvents() {
    if (window.__vmPlaylistStatusBound) {
        return;
    }
    window.__vmPlaylistStatusBound = true;
    window.addEventListener("vm:playlist-status", (event) => {
        const message = event?.detail?.message;
        const tone = event?.detail?.tone;
        if (typeof message !== "string") {
            return;
        }
        setPlaylistStatus(message, tone === "error" || tone === "success" ? tone : "neutral");
    });
}

// Header rendering
function updateSubtitle(aggregated) {
    const subtitle = document.querySelector("#playlist-header .playlist-subtitle");
    if (!subtitle) return;
    const total = aggregated?.totalTracks ?? aggregated?.tracks?.filter(Boolean).length ?? 0;
    subtitle.textContent = `${total} tracks`;
}

function renderHeader(aggregated) {
    const header = document.getElementById("playlist-header");
    if (!header) return;
    header.textContent = "";
    
    const coverImg = document.createElement("img");
    coverImg.src = aggregated?.playlistCoverUrl || PLACEHOLDER_IMG;
    coverImg.alt = "Playlist Cover";
    
    const headerInfo = document.createElement("div");
    headerInfo.className = "header-info";
    
    const titleDiv = document.createElement("div");
    titleDiv.className = "playlist-title";
    if (aggregated?.playlistUrl) {
        const link = document.createElement("a");
        link.href = aggregated.playlistUrl;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.textContent = aggregated?.playlistName || "Playlist";
        titleDiv.appendChild(link);
    } else {
        titleDiv.textContent = aggregated?.playlistName || "Playlist";
    }
    
    const subtitle = document.createElement("div");
    subtitle.className = "playlist-subtitle";
    
    headerInfo.appendChild(titleDiv);
    headerInfo.appendChild(subtitle);
    header.appendChild(coverImg);
    header.appendChild(headerInfo);
    updateSubtitle(aggregated);
}

// Track rendering
function renderTracks(aggregated, options = {}) {
    const container = document.getElementById("playlist");
    if (!container) return;
    
    container.dataset.viewMode = state.viewMode || DEFAULT_VIEW_MODE;
    const startIndex = options.startIndex ?? 0;
    
    if (options.reset) {
        container.textContent = "";
        clearTrackRegistry();
    }
    
    const tracks = aggregated?.tracks || [];
    if (!tracks.length && options.reset) {
        const empty = document.createElement("div");
        empty.className = "playlist-empty";
        empty.textContent = "No tracks found.";
        container.appendChild(empty);
        return;
    }
    
    for (let i = startIndex; i < tracks.length; i++) {
        const track = tracks[i];
        if (!track) continue;
        container.appendChild(createTrackElement(track, i, state));
    }
    refreshLibraryBadgesLocally(null, state);
}

// Load more handling
function updateLoadMore(aggregated) {
    const wrapper = document.getElementById("load-more-wrapper");
    const btn = document.getElementById("load-more");
    if (!wrapper || !btn) return;
    
    if (aggregated?.hasMore) {
        wrapper.classList.remove("hidden");
        btn.disabled = false;
    } else {
        wrapper.classList.add("hidden");
    }
}

// Curation panel integration
function applyManualDiscogsUrl(item, url) {
    const safeUrl = safeDiscogsUrl(url);
    if (!Array.isArray(state.aggregated?.tracks) || !safeUrl) return;
    
    for (let i = 0; i < state.aggregated.tracks.length; i++) {
        const track = state.aggregated.tracks[i];
        if (!track) continue;
        
        const artistMatch = normalizeForSearch(primaryArtist(track.artist) || track.artist) === normalizeForSearch(item.artist);
        const albumMatch = normalizeForSearch(track.album) === normalizeForSearch(item.album);
        const yearMatch = (typeof track.releaseYear === "number" ? track.releaseYear : null) === (item.releaseYear ?? null);
        
        if (artistMatch && albumMatch && yearMatch) {
            track.discogsAlbumUrl = safeUrl;
            track.discogsStatus = "found";
            const key = buildTrackKey(track, i);
            if (key) discogsState.completed.add(key);
            markDiscogsResult(key, i, safeUrl, state, (delay) => scheduleLibraryRefresh(delay, state));
        }
    }
    
    item.discogsAlbumUrl = safeUrl;
    scheduleLibraryRefresh(200, state);
}

let curationSetupPromise = null;
function setupCurationPanel() {
    if (curationSetupPromise) return curationSetupPromise;
    
    curationSetupPromise = initCurationPanel({
        placeholderImage: PLACEHOLDER_IMG,
        buildQueue: () => buildCurationQueue(state.aggregated?.tracks),
        onCandidateSaved: (item, url) => applyManualDiscogsUrl(item, url),
    }).catch((error) => {
        console.warn("Curation panel could not be initialized", error);
        return null;
    });
    
    return curationSetupPromise;
}

// API functions
function chunkQuery(id, offset, limit) {
    return new URLSearchParams({
        id,
        offset: String(Math.max(0, offset)),
        limit: String(Math.max(1, limit)),
    });
}
async function requestPlaylistChunk(id, offset, limit) {
    const response = await fetch(`/api/playlist?${chunkQuery(id, offset, limit).toString()}`, { cache: "no-cache" });
    if (!response.ok) {
        const apiError = await readApiError(response);
        const message = getPlaylistLoadErrorMessage(response, apiError, `HTTP ${response.status}`);
        const error = new Error(message);
        error.apiCode = apiError?.code ?? null;
        error.httpStatus = response.status;
        throw error;
    }
    return response.json();
}
async function consumePrefetchedChunk(id, offset, limit) {
    const prefetched = state.prefetchedChunk;
    if (!prefetched) {
        return null;
    }
    if (prefetched.id !== id || prefetched.offset !== offset || prefetched.limit !== limit) {
        return null;
    }
    if (prefetched.promise) {
        try {
            await prefetched.promise;
        }
        catch (_a) {
        }
    }
    if (state.prefetchedChunk !== prefetched) {
        return null;
    }
    const payload = prefetched.payload ?? null;
    state.prefetchedChunk = null;
    return payload;
}
function prefetchNextChunk() {
    if (!state.id || !state.aggregated?.hasMore) {
        state.prefetchedChunk = null;
        return;
    }
    const offset = typeof state.aggregated.nextOffset === "number"
        ? state.aggregated.nextOffset
        : state.aggregated.tracks?.length ?? 0;
    const limit = Math.max(1, state.pageSize || DEFAULT_PAGE_SIZE);
    const existing = state.prefetchedChunk;
    if (existing
        && existing.id === state.id
        && existing.offset === offset
        && existing.limit === limit
        && (existing.promise || existing.payload)) {
        return;
    }
    const next = {
        id: state.id,
        offset,
        limit,
        payload: null,
        promise: null,
    };
    state.prefetchedChunk = next;
    next.promise = requestPlaylistChunk(state.id, offset, limit)
        .then((payload) => {
        if (state.prefetchedChunk !== next) {
            return null;
        }
        next.payload = payload;
        return payload;
    })
        .catch(() => {
        if (state.prefetchedChunk === next) {
            state.prefetchedChunk = null;
        }
        return null;
    })
        .finally(() => {
        if (state.prefetchedChunk === next) {
            next.promise = null;
        }
    });
}
async function fetchPlaylistChunk(id, offset, limit, { reset } = { reset: false }, prefetchedPayload = null) {
    const payload = prefetchedPayload ?? await requestPlaylistChunk(id, offset, limit);
    const previous = state.aggregated;
    const merged = storePlaylistChunk(id, payload, previous);
    const prevLength = previous?.tracks?.length ?? 0;
    state.aggregated = merged;
    
    // Only render tracks if album selection is complete (for initial load with reset=true)
    // or if it's a subsequent load (reset=false)
    if (reset && !state.albumSelectionComplete) {
        // Don't render yet - wait for album selection
        renderHeader(merged);
    } else if (reset) {
        renderHeader(merged);
        renderTracks(merged, { reset: true });
    } else {
        renderTracks(merged, { startIndex: prevLength });
        updateSubtitle(merged);
    }
    
    updateLoadMore(merged);
    
    const loadedOffset = typeof payload?.offset === "number" ? payload.offset : offset;
    const loadedTracks = Array.isArray(payload?.tracks) ? payload.tracks : [];
    
    // Only start Discogs lookups if album selection is complete
    if (loadedTracks.length && (state.albumSelectionComplete || !reset)) {
        const lookupTask = queueDiscogsLookups(loadedOffset, loadedTracks, state, (delay) => scheduleLibraryRefresh(delay, state));
        if (lookupTask && typeof lookupTask.catch === "function") {
            lookupTask.catch((error) => {
                console.warn("Discogs lookups failed:", error);
            });
        }
    }
    return payload;
}

// Album extraction from tracks
async function extractAlbumsFromTracks(tracks) {
    if (!tracks || tracks.length === 0) return [];
    
    try {
        const response = await fetch("/api/albums/extract", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ tracks }),
        });
        
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        
        const data = await response.json();
        return data.albums || [];
    } catch (error) {
        console.warn("Album extraction failed:", error);
        return [];
    }
}

// Auto-select all albums without showing modal
async function handleAlbumSelection(tracks) {
    const albums = await extractAlbumsFromTracks(tracks);
    state.albums = albums;
    // Auto-select all albums, but fall back to all tracks when extraction yields no albums.
    state.selectedAlbums = albums.length > 0 ? albums : null;
    state.albumSelectionComplete = true;
    return albums;
}

// Get tracks for selected albums only
function getTracksForSelectedAlbums() {
    if (!state.selectedAlbums || !state.aggregated?.tracks) {
        return state.aggregated?.tracks || [];
    }
    
    // Create a set of all track indices that belong to selected albums
    const selectedIndices = new Set();
    state.selectedAlbums.forEach(album => {
        if (album.trackIndices) {
            album.trackIndices.forEach(idx => selectedIndices.add(idx));
        }
    });
    
    // Filter tracks
    return state.aggregated.tracks.filter((_, index) => selectedIndices.has(index));
}

// Main load function
async function loadPlaylist(id, pageSize = DEFAULT_PAGE_SIZE) {
    const header = document.getElementById("playlist-header");
    const container = document.getElementById("playlist");
    
    if (!header || !container) {
        console.error("Missing DOM elements (#playlist-header or #playlist).");
        return;
    }

    setupDiscogsDrawer();
    registerPlaylistStatusEvents();
    
    // Load custom vendor configuration (non-blocking)
    loadCustomVendors();
    
    // Initialize view toggle with render function
    initViewToggle(state, renderTracks);
    setupDiscogsPanel(state);
    await setupCurationPanel();
    applyViewMode(state.viewMode, state, renderTracks, { rerender: false });
    
    Object.assign(state, {
        id: id || null,
        pageSize,
        aggregated: null,
        prefetchedChunk: null,
        viewMode: state.viewMode || getStoredViewMode(),
        albums: null,
        selectedAlbums: null,
        albumSelectionComplete: false,
    });
    
    resetDiscogsState();
    
    const cached = readCachedPlaylist(id || undefined);
    if (!state.id && cached?.id) {
        state.id = cached.id;
    }
    
    if (!state.id) {
        container.textContent = "No playlist ID provided and no cached playlist available.";
        setPlaylistStatus("No playlist selected.", "error");
        return;
    }
    
    showOverlay("Loading playlist…");
    
    try {
        // Load first chunk of playlist
        await fetchPlaylistChunk(state.id, 0, pageSize, { reset: true });
        
        if (state.aggregated?.tracks?.length > 0) {
            hideOverlay();
            
            // Auto-select all albums
            const selectedAlbums = await handleAlbumSelection(state.aggregated.tracks);
            const filteredTracks = selectedAlbums.length > 0 ? getTracksForSelectedAlbums() : state.aggregated.tracks;
            if (selectedAlbums.length === 0) {
                setPlaylistStatus("Album extraction unavailable. Showing all tracks.");
            }
            
            // Update the aggregated tracks for display
            state.aggregated = {
                ...state.aggregated,
                tracks: filteredTracks,
            };
            
            renderHeader(state.aggregated);
            renderTracks(state.aggregated, { reset: true });
            updateLoadMore(state.aggregated);
            
            // Start Discogs lookup for filtered tracks
            if (filteredTracks.length > 0) {
                const lookupTask = queueDiscogsLookups(0, filteredTracks, state, (delay) => scheduleLibraryRefresh(delay, state));
                if (lookupTask && typeof lookupTask.catch === "function") {
                    lookupTask.catch((error) => {
                        console.warn("Discogs lookups failed:", error);
                    });
                }
            }
            prefetchNextChunk();
        }
    } catch (e) {
        console.error("Failed to load playlist:", e);
        const message = e instanceof Error ? e.message : String(e);
        container.textContent = message;
        setPlaylistStatus(message, "error");
    } finally {
        hideOverlay();
    }
    
    // Load more button handler
    const btn = document.getElementById("load-more");
    if (btn) {
        let loadMoreInFlight = false;
        btn.addEventListener("click", async () => {
            if (!state.aggregated?.hasMore || loadMoreInFlight) return;
            loadMoreInFlight = true;
            
            const offset = typeof state.aggregated.nextOffset === "number"
                ? state.aggregated.nextOffset
                : state.aggregated.tracks?.length ?? 0;
            
            btn.disabled = true;
            const originalText = btn.textContent || "Load more";
            btn.textContent = "Loading…";
            btn.setAttribute("aria-busy", "true");
            
            try {
                const prefetched = await consumePrefetchedChunk(state.id, offset, state.pageSize);
                await fetchPlaylistChunk(state.id, offset, state.pageSize, { reset: false }, prefetched);
                prefetchNextChunk();
                setPlaylistStatus("");
            } catch (e) {
                setPlaylistStatus("More tracks could not be loaded: " + (e instanceof Error ? e.message : String(e)), "error");
            } finally {
                btn.disabled = false;
                btn.removeAttribute("aria-busy");
                btn.textContent = originalText;
                loadMoreInFlight = false;
            }
        }, { once: false });
    }
}

export { loadPlaylist };

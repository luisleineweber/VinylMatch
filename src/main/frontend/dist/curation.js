import { injectHeader } from "./common/header.js";
import { getPlaylistLoadErrorMessage, readApiError } from "./common/api-errors.js";
import { buildCurationQueue, normalizeForSearch, primaryArtist } from "./common/playlist-utils.js";
import { readCachedPlaylist, storePlaylistChunk } from "./storage.js";

const DEFAULT_TEMPLATE = "";
const PAGE_SIZE = 50;
const PLACEHOLDER_IMG = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";

const curationState = {
    queue: [],
    index: 0,
    candidates: [],
    jev: null,
    requestId: 0,
    loading: false,
    saving: false,
};

const pageState = {
    id: null,
    aggregated: null,
    curation: null,
    loading: false,
};
let curationStatusTimer = null;

function safeDiscogsUrl(url) {
    try {
        const parsed = new URL(url, window.location.origin);
        const host = parsed.hostname.toLowerCase();
        if ((parsed.protocol === "https:" || parsed.protocol === "http:") && host.endsWith("discogs.com")) {
            return parsed.href;
        }
        return null;
    }
    catch (_a) {
        return null;
    }
}

function safeHttpUrl(url) {
    try {
        const parsed = new URL(url, window.location.origin);
        if (parsed.protocol === "https:" || parsed.protocol === "http:") {
            return parsed.href;
        }
        return null;
    }
    catch (_a) {
        return null;
    }
}

function safeDiscogsImage(url) {
    return safeDiscogsUrl(url);
}

function select(container, selector) {
    return container?.querySelector(selector);
}

async function injectTemplate(container, templateUrl) {
    if (!container || !templateUrl)
        return;
    try {
        const res = await fetch(templateUrl);
        if (!res.ok) {
            throw new Error(`HTTP ${res.status}`);
        }
        container.innerHTML = await res.text();
    }
    catch (error) {
        console.warn("Failed to load curation template", error);
        container.innerHTML = "<p class=\"muted\">Curation panel could not be loaded.</p>";
    }
}

function resetCurationUi(container, message) {
    const album = select(container, "#curation-album");
    const grid = select(container, "#curation-candidates");
    const empty = select(container, "#curation-empty");
    if (album)
        album.textContent = "";
    if (grid)
        grid.textContent = "";
    if (empty) {
        if (message)
            empty.textContent = message;
        empty.classList.remove("hidden");
    }
    renderJevDecision(container, null);
}

function renderJevDecision(container, decision) {
    const node = select(container, "#curation-jev");
    if (!node) return;
    const status = decision?.status;
    if (!status || status === "skipped") {
        node.textContent = "";
        node.classList.add("hidden");
        node.classList.remove("error");
        return;
    }
    const confidence = typeof decision.confidence === "number"
        ? ` Jev confidence: ${Math.round(decision.confidence * 100)}%. This shows how strongly Jev favors its answer.`
        : "";
    node.textContent = `${decision.message || "Review the candidates yourself."}${confidence}`;
    node.classList.toggle("error", status === "error");
    node.classList.remove("hidden");
}

function setCurationStatus(message, tone = "neutral") {
    const node = document.getElementById("curation-status");
    if (!node) {
        return;
    }
    if (curationStatusTimer) {
        clearTimeout(curationStatusTimer);
        curationStatusTimer = null;
    }
    if (!message || tone === "neutral") {
        node.textContent = "";
        node.classList.add("hidden");
        node.classList.remove("error", "success");
        return;
    }
    node.textContent = message;
    node.classList.remove("hidden", "error", "success");
    if (tone === "error") {
        node.classList.add("error");
        return;
    }
    if (tone === "success") {
        node.classList.add("success");
        curationStatusTimer = window.setTimeout(() => {
            node.textContent = "";
            node.classList.add("hidden");
            node.classList.remove("error", "success");
            curationStatusTimer = null;
        }, 2600);
    }
}

function updateCurationProgress(container) {
    const progress = select(container, "#curation-progress");
    if (!progress)
        return;
    if (!curationState.queue.length) {
        progress.textContent = "0 / 0";
        return;
    }
    progress.textContent = `${curationState.index + 1} / ${curationState.queue.length}`;
}

function renderCurationAlbum(container, item, placeholderImage) {
    const album = select(container, "#curation-album");
    const empty = select(container, "#curation-empty");
    if (!album)
        return;
    album.textContent = "";
    if (empty)
        empty.classList.add("hidden");
    if (!item) {
        resetCurationUi(container, "No playlist.");
        return;
    }
    const img = document.createElement("img");
    img.className = "thumb";
    img.src = item.coverUrl || placeholderImage;
    img.alt = item.album || "Album Cover";
    const meta = document.createElement("div");
    meta.className = "meta";
    const title = document.createElement("div");
    title.className = "title";
    title.textContent = item.album || "Unknown album";
    const artist = document.createElement("div");
    artist.className = "artist";
    artist.textContent = item.artist || "Unknown artist";
    const hint = document.createElement("div");
    hint.className = "hint";
    hint.textContent = item.trackName ? `from: ${item.trackName}` : "Playlist album";
    meta.appendChild(title);
    meta.appendChild(artist);
    meta.appendChild(hint);
    if (Array.isArray(item.missing) && item.missing.length) {
        const missing = document.createElement("div");
        missing.className = "missing";
        missing.textContent = `Missing: ${item.missing.join(" • ")}`;
        meta.appendChild(missing);
    }
    album.appendChild(img);
    album.appendChild(meta);
}

function createCandidateCard(container, item, candidate, onCandidateSaved) {
    const source = candidate.source || "Discogs";
    const isDiscogs = source.toLowerCase().includes("discogs");
    const safeUrl = isDiscogs ? safeDiscogsUrl(candidate.url) : safeHttpUrl(candidate.url);
    const safeThumb = isDiscogs ? safeDiscogsImage(candidate.thumb) : safeHttpUrl(candidate.thumb) || candidate.thumb;
    if (!safeUrl) {
        return null;
    }
    const card = document.createElement("div");
    card.className = "candidate-card";
    const bar = document.createElement("div");
    bar.className = "candidate-browser";
    const origin = document.createElement("span");
    origin.className = "candidate-source";
    origin.textContent = source;
    const urlLabel = document.createElement("div");
    urlLabel.className = "url";
    urlLabel.textContent = safeUrl || "no URL";
    const openLink = document.createElement("a");
    openLink.href = safeUrl || "#";
    openLink.target = "_blank";
    openLink.rel = "noopener noreferrer";
    openLink.textContent = "Open";
    bar.appendChild(origin);
    bar.appendChild(urlLabel);
    bar.appendChild(openLink);
    const preview = document.createElement("div");
    preview.className = "candidate-preview";
    if (safeThumb) {
        const img = document.createElement("img");
        img.src = safeThumb;
        img.alt = candidate.title || "Discogs Vorschau";
        preview.appendChild(img);
    }
    else {
        const placeholder = document.createElement("div");
        placeholder.className = "placeholder";
        placeholder.textContent = "No preview";
        preview.appendChild(placeholder);
    }
    const meta = document.createElement("div");
    meta.className = "candidate-meta";
    const title = document.createElement("div");
    title.className = "title";
    title.textContent = candidate.title || "Untitled";
    const details = document.createElement("div");
    details.className = "details";
    const detailParts = [];
    if (candidate.artist)
        detailParts.push(candidate.artist);
    if (candidate.year)
        detailParts.push(candidate.year);
    if (candidate.country)
        detailParts.push(candidate.country);
    if (candidate.format)
        detailParts.push(candidate.format);
    details.textContent = detailParts.join(" • ") || "Discogs result";
    meta.appendChild(title);
    meta.appendChild(details);
    if (curationState.jev?.status === "suggested" && curationState.jev.releaseId === candidate.releaseId) {
        const suggestion = document.createElement("div");
        suggestion.className = "candidate-jev-suggestion";
        suggestion.textContent = "Jev suggestion · Review before saving";
        meta.appendChild(suggestion);
    }
    const actions = document.createElement("div");
    actions.className = "candidate-actions";
    const selectButton = document.createElement("button");
    selectButton.type = "button";
    selectButton.className = isDiscogs ? "btn" : "btn ghost";
    selectButton.textContent = isDiscogs ? "Save match" : "Open in browser";
    if (isDiscogs) {
        selectButton.addEventListener("click", () => selectCandidate(container, item, candidate, selectButton, onCandidateSaved));
    }
    else {
        selectButton.addEventListener("click", () => window.open(safeUrl, "_blank", "noopener"));
    }
    actions.appendChild(selectButton);
    card.appendChild(bar);
    card.appendChild(preview);
    card.appendChild(meta);
    card.appendChild(actions);
    return card;
}

function renderCurationCandidates(container, item, candidates, onCandidateSaved) {
    const grid = select(container, "#curation-candidates");
    const empty = select(container, "#curation-empty");
    if (!grid)
        return;
    grid.textContent = "";
    if (!Array.isArray(candidates) || !candidates.length) {
        if (empty) {
            empty.textContent = "No candidates.";
            empty.classList.remove("hidden");
        }
        return;
    }
    if (empty)
        empty.classList.add("hidden");
    for (const candidate of candidates) {
        const card = createCandidateCard(container, item, candidate, onCandidateSaved);
        if (card) {
            grid.appendChild(card);
        }
    }
}

async function loadCurationCandidates(container, item) {
    if (!item)
        return [];
    curationState.loading = true;
    const empty = select(container, "#curation-empty");
    if (empty) {
        empty.textContent = "Loading candidates...";
        empty.classList.remove("hidden");
    }
    try {
        const res = await fetch("/api/discogs/curation/candidates", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                artist: item.artist,
                album: item.album,
                year: item.releaseYear,
                trackTitle: item.trackName,
                preferVinyl: true,
                format: "vinyl",
            }),
        });
        if (!res.ok) {
            const apiError = await readApiError(res);
            throw new Error(apiError?.message || "HTTP " + res.status);
        }
        const payload = await res.json();
        const discogsCandidates = Array.isArray(payload?.candidates)
            ? payload.candidates
            : [];
        const normalizedDiscogs = discogsCandidates.map((candidate) => ({
            ...candidate,
            source: "Discogs",
        }));
        const query = [item.artist, item.album].filter(Boolean).join(" ");
        const searchTerm = encodeURIComponent(`${query} vinyl`);
        const storeCandidates = [
            {
                source: "Discogs (Google)",
                url: `https://www.google.com/search?q=${encodeURIComponent("site:discogs.com " + query)}`,
                title: item.album || "Discogs search",
                thumb: item.coverUrl,
                artist: item.artist,
                year: item.releaseYear,
            },
            {
                source: "HHV",
                url: `https://www.google.com/search?q=${encodeURIComponent("site:hhv.de " + query + " vinyl")}`,
                title: "HHV result",
                thumb: item.coverUrl,
                artist: item.artist,
                year: item.releaseYear,
            },
            {
                source: "JPC",
                url: `https://www.google.com/search?q=${encodeURIComponent("site:jpc.de " + query + " vinyl")}`,
                title: "JPC result",
                thumb: item.coverUrl,
                artist: item.artist,
                year: item.releaseYear,
            },
            {
                source: "Amazon",
                url: `https://www.google.com/search?q=${encodeURIComponent("site:amazon.de " + searchTerm)}`,
                title: "Amazon result",
                thumb: item.coverUrl,
                artist: item.artist,
                year: item.releaseYear,
            },
        ];
        return { candidates: [...normalizedDiscogs, ...storeCandidates], jev: payload?.jev || null };
    }
    catch (e) {
        console.warn("Failed to load curation candidates", e);
        return {
            candidates: [],
            jev: null,
            error: "Candidate load failed: " + (e instanceof Error ? e.message : String(e)),
        };
    }
    finally {
        curationState.loading = false;
    }
}

async function selectCandidate(container, item, candidate, button, onCandidateSaved) {
    const safeUrl = safeDiscogsUrl(candidate?.url);
    if (!safeUrl || curationState.saving)
        return;
    curationState.saving = true;
    const original = button.textContent;
    button.textContent = "Saving…";
    button.disabled = true;
    try {
        const res = await fetch("/api/discogs/curation/save", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                artist: item.artist,
                album: item.album,
                year: item.releaseYear,
                trackTitle: item.trackName,
                url: safeUrl,
                thumb: safeDiscogsImage(candidate.thumb),
            }),
        });
        if (!res.ok)
            throw new Error("HTTP " + res.status);
        button.textContent = "Saved";
        onCandidateSaved?.(item, candidate.url);
        setTimeout(() => (button.textContent = original), 1200);
    }
    catch (e) {
        setCurationStatus("Could not save link: " + (e instanceof Error ? e.message : String(e)), "error");
        button.textContent = original;
    }
    finally {
        button.disabled = false;
        curationState.saving = false;
    }
}

async function showCurationItem(container, step, placeholderImage, onCandidateSaved) {
    const requestId = ++curationState.requestId;
    if (!curationState.queue.length) {
        resetCurationUi(container, "No playlist.");
        updateCurationProgress(container);
        return;
    }
    curationState.index = Math.min(Math.max(0, curationState.index + step), curationState.queue.length - 1);
    const current = curationState.queue[curationState.index];
    renderCurationAlbum(container, current, placeholderImage);
    setCurationStatus("");
    curationState.jev = null;
    renderJevDecision(container, null);
    updateCurationProgress(container);
    const result = await loadCurationCandidates(container, current);
    if (requestId !== curationState.requestId) return;
    curationState.candidates = result.candidates;
    curationState.jev = result.jev;
    if (result.error) setCurationStatus(result.error, "error");
    renderJevDecision(container, curationState.jev);
    renderCurationCandidates(container, current, curationState.candidates, onCandidateSaved);
}

async function initCurationPanel(options = {}) {
    const container = options.container ?? document.getElementById(options.containerId ?? "curation-panel-container");
    if (!container)
        return null;
    const templatePath = options.templateUrl ?? container.dataset.template ?? DEFAULT_TEMPLATE;
    if (templatePath) {
        await injectTemplate(container, templatePath);
    }
    resetCurationUi(container, "Idle.");
    updateCurationProgress(container);
    const placeholderImage = options.placeholderImage ?? "";
    const buildQueue = typeof options.buildQueue === "function" ? options.buildQueue : () => [];
    const onCandidateSaved = typeof options.onCandidateSaved === "function" ? options.onCandidateSaved : () => {};
    const startBtn = select(container, "#curation-start");
    const nextBtn = select(container, "#curation-next");
    const prevBtn = select(container, "#curation-prev");
    startBtn?.addEventListener("click", () => {
        curationState.queue = buildQueue();
        curationState.index = 0;
        if (!curationState.queue.length) {
            resetCurationUi(container, "No tracks.");
            updateCurationProgress(container);
            return;
        }
        showCurationItem(container, 0, placeholderImage, onCandidateSaved);
    });
    nextBtn?.addEventListener("click", () => showCurationItem(container, 1, placeholderImage, onCandidateSaved));
    prevBtn?.addEventListener("click", () => showCurationItem(container, -1, placeholderImage, onCandidateSaved));
    return {
        refreshQueue: () => {
            curationState.queue = buildQueue();
            curationState.index = Math.min(curationState.index, Math.max(0, curationState.queue.length - 1));
            updateCurationProgress(container);
        },
    };
}

function showLoading(message = "Loading…") {
    const overlay = document.getElementById("global-loading");
    const text = document.getElementById("global-loading-text");
    overlay?.classList.remove("hidden");
    overlay?.classList.add("visible");
    if (text)
        text.textContent = message;
}

function hideLoading() {
    const overlay = document.getElementById("global-loading");
    overlay?.classList.remove("visible");
    overlay?.classList.add("hidden");
}


function applyManualDiscogsUrl(item, url) {
    const safeUrl = typeof url === "string" && url ? url : null;
    if (!Array.isArray(pageState.aggregated?.tracks) || !safeUrl)
        return;
    for (const track of pageState.aggregated.tracks) {
        if (!track)
            continue;
        const artistMatch = normalizeForSearch(primaryArtist(track.artist) || track.artist) === normalizeForSearch(item.artist);
        const albumMatch = normalizeForSearch(track.album) === normalizeForSearch(item.album);
        const yearMatch = (typeof track.releaseYear === "number" ? track.releaseYear : null) === (item.releaseYear ?? null);
        if (artistMatch && albumMatch && yearMatch) {
            track.discogsAlbumUrl = safeUrl;
        }
    }
    item.discogsAlbumUrl = safeUrl;
    pageState.curation?.refreshQueue();
}

function updateSummary() {
    const title = document.getElementById("curation-summary-title");
    const details = document.getElementById("curation-summary-details");
    if (!title || !details)
        return;
    if (!pageState.aggregated) {
        title.textContent = "No playlist";
        details.textContent = "-";
        return;
    }
    title.textContent = pageState.aggregated.playlistName || "Playlist";
    const trackCount = Array.isArray(pageState.aggregated.tracks) ? pageState.aggregated.tracks.length : 0;
    details.textContent = `${trackCount} tracks`;
}

async function fetchPlaylist(id) {
    let offset = 0;
    let aggregated = null;
    let hasMore = true;
    while (hasMore) {
        const query = new URLSearchParams({ id, offset: String(offset), limit: String(PAGE_SIZE) });
        const res = await fetch(`/api/playlist?${query.toString()}`, { cache: "no-cache" });
        if (!res.ok) {
            const apiError = await readApiError(res);
            throw new Error(getPlaylistLoadErrorMessage(res, apiError, `HTTP ${res.status}`));
        }
        const payload = await res.json();
        aggregated = storePlaylistChunk(id, payload, aggregated ?? undefined);
        hasMore = !!aggregated?.hasMore;
        offset = typeof aggregated?.nextOffset === "number" ? aggregated.nextOffset : (offset + PAGE_SIZE);
    }
    return aggregated;
}

function getPlaylistIdFromUrl(value) {
    if (!value)
        return null;
    const trimmed = value.trim();
    const idPattern = /^[A-Za-z0-9]{22}$/;
    if (idPattern.test(trimmed))
        return trimmed;
    try {
        const parsed = new URL(trimmed);
        const hostname = parsed.hostname.toLowerCase();
        const isSpotifyHost = hostname === "spotify.com" || hostname === "open.spotify.com" || hostname === "play.spotify.com" || hostname === "www.spotify.com" || hostname.endsWith(".spotify.com");
        if (!isSpotifyHost)
            return null;
        if (parsed.protocol !== "https:" && parsed.protocol !== "http:")
            return null;
        const segments = parsed.pathname.split("/").filter(Boolean);
        if (segments[0] !== "playlist" || !segments[1] || !idPattern.test(segments[1]))
            return null;
        return segments[1].split("?")[0];
    }
    catch (_a) {
        return null;
    }
}

async function loadPlaylistFromInput() {
    if (pageState.loading)
        return;
    const textarea = document.getElementById("curation-playlist-url");
    const id = textarea ? getPlaylistIdFromUrl(textarea.value.trim()) : null;
    if (!id) {
        setCurationStatus("Please enter a valid Spotify playlist URL or ID.", "error");
        return;
    }
    console.info("Starting playlist load", { playlistId: id });
    pageState.loading = true;
    setCurationStatus("");
    showLoading("Loading…");
    try {
        pageState.id = id;
        pageState.aggregated = await fetchPlaylist(id);
        updateSummary();
        pageState.curation?.refreshQueue();
        setCurationStatus("Playlist loaded.", "success");
    }
    catch (e) {
        console.error("Playlist could not be loaded", e);
        setCurationStatus(e instanceof Error ? e.message : "Playlist load failed.", "error");
    }
    finally {
        hideLoading();
        pageState.loading = false;
    }
}

async function initCurationPage() {
    await injectHeader();
    
    const statusRes = await fetch("/api/auth/status", { cache: "no-cache", credentials: "include" });
    if (statusRes.ok) {
        const status = await statusRes.json();
        if (!status?.loggedIn) {
            showCurationError("Login required.");
            return;
        }
        if (status?.isAdmin !== true) {
            showCurationError("Admin only.");
            return;
        }
    } else {
        showCurationError("Auth check failed.");
        return;
    }
    
    updateSummary();
    pageState.curation = await initCurationPanel({
        placeholderImage: PLACEHOLDER_IMG,
        buildQueue: () => buildCurationQueue(pageState.aggregated?.tracks),
        onCandidateSaved: (item, url) => applyManualDiscogsUrl(item, url),
        containerId: "curation-panel-container",
    });
    const cached = readCachedPlaylist(null);
    if (cached?.data) {
        pageState.id = cached.id;
        pageState.aggregated = cached.data;
        updateSummary();
        pageState.curation?.refreshQueue();
    }
    const btn = document.getElementById("curation-use-link");
    btn?.addEventListener("click", () => loadPlaylistFromInput());
    const input = document.getElementById("curation-playlist-url");
    input?.addEventListener("keydown", (event) => {
        if (event.key !== "Enter") {
            return;
        }
        event.preventDefault();
        loadPlaylistFromInput();
    });
}

function showCurationError(message) {
    const page = document.getElementById("curation-page");
    if (page) {
        page.innerHTML = `
            <section class="curation-hero">
                <div>
                    <h1>Access Denied</h1>
                    <p class="muted">${message}</p>
                </div>
            </section>
        `;
    }
}

export { initCurationPanel };

window.addEventListener("DOMContentLoaded", () => {
    if (document.getElementById("curation-page")) {
        initCurationPage().catch((error) => console.warn("Curation page could not be initialized", error));
    }
});


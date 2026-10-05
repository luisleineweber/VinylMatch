import { injectHeader } from "./common/header.js";
import { fetchWithTimeout, getPlaylistLoadErrorMessage, readApiError } from "./common/api-errors.js";
import { loadPlaylist } from "./playlist.js";
import { readRecents, storePlaylistChunk, readCachedPlaylist } from "./storage.js";
const PLAYLIST_PAGE_SIZE = 20;
let homeStatusTimer = null;
function showGlobalLoading(message = "Loading playlist…") {
    const overlay = document.getElementById("global-loading");
    if (!overlay)
        return;
    overlay.classList.remove("hidden");
    overlay.classList.add("visible");
    const text = document.getElementById("global-loading-text");
    if (text && typeof message === "string") {
        text.textContent = message;
    }
}
function hideGlobalLoading() {
    const overlay = document.getElementById("global-loading");
    if (!overlay)
        return;
    overlay.classList.remove("visible");
    overlay.classList.add("hidden");
}
function showHomeStatus(message, tone = "neutral") {
    const node = document.getElementById("home-status");
    if (!node)
        return;
    if (homeStatusTimer) {
        clearTimeout(homeStatusTimer);
        homeStatusTimer = null;
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
    }
    else if (tone === "success") {
        node.classList.add("success");
        homeStatusTimer = window.setTimeout(() => {
            node.textContent = "";
            node.classList.add("hidden");
            node.classList.remove("error", "success");
            homeStatusTimer = null;
        }, 2400);
    }
}
function createPlaylistCard(item) {
    const card = document.createElement("a");
    card.className = "recent-item";
    card.href = "#";
    card.dataset.playlistId = item.id;
    const img = document.createElement("img");
    img.src = item.coverUrl || "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";
    img.alt = item.name || "Playlist";
    img.width = 44;
    img.height = 44;
    img.loading = "lazy";
    img.decoding = "async";
    const info = document.createElement("div");
    info.className = "title";
    info.textContent = item.name || "Spotify Playlist";
    const metaText = [];
    const trackCount = typeof item.trackCount === "number"
        ? item.trackCount
        : (typeof item.tracksTotal === "number"
            ? item.tracksTotal
            : (typeof item.totalTracks === "number"
                ? item.totalTracks
                : (typeof item.tracks?.total === "number"
                    ? item.tracks.total
                    : null)));
    if (typeof trackCount === "number" && trackCount > 0) {
        metaText.push(`${trackCount} tracks`);
    }
    if (item.owner && item.owner.trim()) {
        metaText.push(`by ${item.owner}`);
    }
    if (metaText.length > 0) {
        const meta = document.createElement("div");
        meta.className = "meta";
        meta.textContent = metaText.join(" • ");
        const wrapper = document.createElement("div");
        wrapper.appendChild(info);
        wrapper.appendChild(meta);
        card.appendChild(img);
        card.appendChild(wrapper);
    }
    else {
        card.appendChild(img);
        card.appendChild(info);
    }
    return card;
}
function renderRecents() {
    const list = document.getElementById("recent-list");
    const empty = document.querySelector('.sidebar-empty[data-context="recent"]');
    if (!list || !empty)
        return;
    list.textContent = "";
    const recents = readRecents();
    if (!recents.length) {
        empty.classList.remove("hidden");
        return;
    }
    empty.classList.add("hidden");
    for (const item of recents.slice(0, 10)) {
        if (!item?.id)
            continue;
        const card = createPlaylistCard(item);
        card.addEventListener("click", (event) => {
            event.preventDefault();
            loadPlaylistAndNavigate(item.id, { title: item.name });
        });
        list.appendChild(card);
    }
}

const POPULAR_PLAYLISTS = [
    {
        id: "37i9dQZF1DXcBWIGoYBM5M",
        name: "Today's Top Hits",
        description: "Spotify's current chart hits",
        owner: "Spotify",
        trackCount: 50,
        coverUrl: "https://charts-images.scdn.co/assets/locale_en/regional/daily/region_global_large.jpg",
    },
    {
        id: "37i9dQZF1DX4JAvHpjipBk",
        name: "Rock Classics",
        description: "Timeless rock favorites",
        owner: "Spotify",
        trackCount: 100,
        coverUrl: "https://i.scdn.co/image/ab67706f00000002ca7be4aa69664d1d2b9eb12b",
    },
];
function renderPopular() {
    const list = document.getElementById("popular-list");
    const empty = document.querySelector('.sidebar-empty[data-context="popular"]');
    if (!list || !empty)
        return;
    list.textContent = "";
    if (!POPULAR_PLAYLISTS.length) {
        empty.classList.remove("hidden");
        return;
    }
    empty.classList.add("hidden");
    for (const item of POPULAR_PLAYLISTS) {
        const card = createPlaylistCard({
            id: item.id,
            name: item.name,
            coverUrl: item.coverUrl,
            owner: item.owner,
            trackCount: item.trackCount,
        });
        const meta = document.createElement("p");
        meta.className = "sidebar-hint";
        meta.textContent = item.description || "Popular playlist";
        card.appendChild(meta);
        card.addEventListener("click", (event) => {
            event.preventDefault();
            loadPlaylistAndNavigate(item.id, { title: item.name });
        });
        list.appendChild(card);
    }
}
function toggleTab(targetId) {
    const tabs = document.querySelectorAll(".sidebar-tab");
    tabs.forEach((tab) => {
        const target = tab.dataset.target;
        const panel = target ? document.getElementById(target) : null;
        const active = target === targetId && !tab.disabled;
        tab.classList.toggle("active", active);
        tab.setAttribute("aria-selected", String(active));
        if (panel) {
            panel.classList.toggle("hidden", !active);
        }
    });
}
function bindSidebarTabKeyboard(tabs) {
    tabs.forEach((tab, index) => {
        tab.addEventListener("keydown", (event) => {
            if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) {
                return;
            }
            event.preventDefault();
            const enabledTabs = tabs.filter((candidate) => candidate instanceof HTMLButtonElement && !candidate.disabled);
            const currentIndex = enabledTabs.indexOf(tab);
            if (!enabledTabs.length || currentIndex === -1) {
                return;
            }
            let nextIndex = currentIndex;
            if (event.key === "ArrowRight") {
                nextIndex = (currentIndex + 1) % enabledTabs.length;
            }
            else if (event.key === "ArrowLeft") {
                nextIndex = (currentIndex - 1 + enabledTabs.length) % enabledTabs.length;
            }
            else if (event.key === "Home") {
                nextIndex = 0;
            }
            else if (event.key === "End") {
                nextIndex = enabledTabs.length - 1;
            }
            enabledTabs[nextIndex].focus();
            enabledTabs[nextIndex].click();
        });
    });
}
window.addEventListener("DOMContentLoaded", () => {
    const isHomePage = !!document.getElementById("home-page");
    const isPlaylistPage = !!document.getElementById("playlist");
    injectHeader();
    if (isHomePage) {
        renderRecents();
        renderPopular();
        const pageContainer = document.getElementById("home-page");
        const sidebarToggle = document.getElementById("sidebar-toggle");
        const sidebar = document.getElementById("home-sidebar");
        const sidebarBackdrop = document.getElementById("sidebar-backdrop");
        const isVisibleFocusable = (node) => node instanceof HTMLElement &&
            node.offsetParent !== null &&
            !node.closest(".hidden,[aria-hidden='true']");
        const getFocusableInSidebar = () => {
            if (!sidebar) {
                return [];
            }
            const nodes = sidebar.querySelectorAll('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
            return Array.from(nodes).filter(isVisibleFocusable);
        };
        let sidebarLastFocus = null;
        const setSidebarOpen = (open) => {
            if (pageContainer) {
                pageContainer.classList.toggle("sidebar-open", open);
            }
            document.body.classList.toggle("drawer-open", !!open);
            if (sidebarToggle) {
                sidebarToggle.setAttribute("aria-expanded", String(Boolean(open)));
            }
            if (sidebar) {
                sidebar.dataset.open = open ? "true" : "false";
                sidebar.setAttribute("aria-hidden", String(!open));
                if ("inert" in sidebar) {
                    sidebar.inert = !open;
                }
            }
            if (sidebarBackdrop) {
                sidebarBackdrop.classList.toggle("hidden", !open);
            }
            if (open) {
                sidebarLastFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
                const focusable = getFocusableInSidebar();
                focusable[0]?.focus();
            }
            else if (sidebarLastFocus) {
                sidebarLastFocus.focus();
                sidebarLastFocus = null;
            }
        };
        sidebarToggle?.addEventListener("click", () => {
            const isOpen = pageContainer?.classList.contains("sidebar-open");
            setSidebarOpen(!isOpen);
        });
        sidebarBackdrop?.addEventListener("click", () => setSidebarOpen(false));
        window.addEventListener("keydown", (event) => {
            if (event.key === "Escape") {
                setSidebarOpen(false);
                return;
            }
            if (event.key !== "Tab" || !pageContainer?.classList.contains("sidebar-open")) {
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
        setSidebarOpen(false);
        const recentTab = document.getElementById("recent-tab");
        const popularTab = document.getElementById("popular-tab");
        const userTab = document.getElementById("user-tab");
        const userPanel = document.getElementById("user-panel");
        const userList = document.getElementById("user-playlist-list");
        const userEmpty = document.querySelector('.sidebar-empty[data-context="user"]');
        const userControls = document.getElementById("user-controls");
        const userSearchInput = document.getElementById("user-playlist-search");
        const userSortSelect = document.getElementById("user-playlist-sort");
        const userPagination = document.getElementById("user-pagination");
        const userPrev = document.getElementById("user-prev-page");
        const userNext = document.getElementById("user-next-page");
        const userPageInfo = document.getElementById("user-page-info");
        const userStatus = document.getElementById("user-status");
        const getUserPageSize = () => {
            const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
            return Math.max(6, Math.min(9, Math.floor((viewportHeight - 408) / 62)));
        };
        const collator = new Intl.Collator("de", { sensitivity: "base" });
        const userState = {
            items: new Map(),
            total: 0,
            loading: false,
            error: null,
            backgroundLoading: false,
            backgroundFailed: false,
            fullyLoaded: false,
            page: 1,
            pageSize: getUserPageSize(),
            query: "",
            sort: "name-asc",
        };
        let isLoggedIn = false;
        const normalize = (value) => (value || "").toString().toLowerCase();
        const mergeUserPlaylists = (items) => {
            if (!Array.isArray(items))
                return;
            for (const item of items) {
                if (!item?.id)
                    continue;
                userState.items.set(item.id, item);
            }
        };
        const getFilteredItems = () => {
            let entries = Array.from(userState.items.values());
            const query = userState.query.trim().toLowerCase();
            if (query) {
                entries = entries.filter((item) => normalize(item.name).includes(query));
            }
            switch (userState.sort) {
                case "name-desc":
                    entries.sort((a, b) => collator.compare(normalize(b.name), normalize(a.name)));
                    break;
                case "tracks-desc":
                    entries.sort((a, b) => (b.trackCount ?? 0) - (a.trackCount ?? 0) || collator.compare(normalize(a.name), normalize(b.name)));
                    break;
                case "tracks-asc":
                    entries.sort((a, b) => (a.trackCount ?? 0) - (b.trackCount ?? 0) || collator.compare(normalize(a.name), normalize(b.name)));
                    break;
                default:
                    entries.sort((a, b) => collator.compare(normalize(a.name), normalize(b.name)));
                    break;
            }
            return entries;
        };
        const updateControlsVisibility = (hasData) => {
            if (userControls) {
                userControls.classList.toggle("hidden", !hasData);
            }
        };
        const syncUserPageSize = () => {
            const nextPageSize = getUserPageSize();
            if (nextPageSize === userState.pageSize) {
                return;
            }
            const currentStart = Math.max(0, (userState.page - 1) * userState.pageSize);
            userState.pageSize = nextPageSize;
            userState.page = Math.floor(currentStart / nextPageSize) + 1;
            renderUserPlaylists();
        };
        const renderUserPlaylists = () => {
            if (!userPanel || !userList || !userEmpty)
                return;
            const hasData = userState.items.size > 0;
            if (!isLoggedIn) {
                userPanel.setAttribute("aria-disabled", "true");
                userPanel.classList.add("hidden");
                userEmpty.textContent = "Log in with Spotify to see your playlists.";
                userEmpty.classList.remove("hidden");
                updateControlsVisibility(false);
                if (userStatus) {
                    userStatus.textContent = "";
                    userStatus.classList.add("hidden");
                }
                return;
            }
            userPanel.setAttribute("aria-disabled", "false");
            const filtered = getFilteredItems();
            const totalPages = Math.max(1, Math.ceil(filtered.length / userState.pageSize));
            if (userState.page > totalPages)
                userState.page = totalPages;
            const start = (userState.page - 1) * userState.pageSize;
            const pageItems = filtered.slice(start, start + userState.pageSize);
            userList.textContent = "";
            let emptyMessage = "No playlists found.";
            if (userState.error === "login") {
                emptyMessage = "Please log in with Spotify again.";
            }
            else if (userState.error) {
                emptyMessage = "Your playlists could not be loaded.";
            }
            else if (userState.loading && !hasData) {
                emptyMessage = "Loading playlists…";
            }
            else if (!hasData) {
                emptyMessage = "No playlists available.";
            }
            const showEmpty = !pageItems.length;
            if (showEmpty) {
                userEmpty.textContent = emptyMessage;
                userEmpty.classList.remove("hidden");
            }
            else {
                userEmpty.classList.add("hidden");
                for (const playlist of pageItems) {
                    if (!playlist?.id)
                        continue;
                    const card = createPlaylistCard(playlist);
                    card.addEventListener("click", (event) => {
                        event.preventDefault();
                        loadPlaylistAndNavigate(playlist.id, { title: playlist.name });
                    });
                    userList.appendChild(card);
                }
            }
            updateControlsVisibility(hasData);
            if (userPagination) {
                userPagination.classList.toggle("hidden", filtered.length <= userState.pageSize);
                if (userPrev)
                    userPrev.disabled = userState.page <= 1;
                if (userNext)
                    userNext.disabled = userState.page >= totalPages;
                if (userPageInfo)
                    userPageInfo.textContent = `Page ${Math.min(userState.page, totalPages)} of ${totalPages}`;
            }
            if (userStatus) {
                if (userState.backgroundLoading) {
                    userStatus.textContent = `Loading more playlists… (${userState.items.size}/${userState.total || "?"})`;
                    userStatus.classList.remove("hidden");
                }
                else if (!userState.fullyLoaded && userState.items.size > 0 && userState.total > userState.items.size) {
                    userStatus.textContent = `Loaded: ${userState.items.size} of ${userState.total}`;
                    userStatus.classList.remove("hidden");
                }
                else if (userState.backgroundFailed) {
                    userStatus.textContent = "Additional playlists could not be fully loaded.";
                    userStatus.classList.remove("hidden");
                }
                else {
                    userStatus.textContent = "";
                    userStatus.classList.add("hidden");
                }
            }
        };
        const fetchUserPlaylistsPage = async (offset = 0) => {
            const params = new URLSearchParams({ offset: String(Math.max(0, offset)), limit: "50" });
            const res = await fetch(`/api/user/playlists?${params.toString()}`, { cache: "no-cache" });
            if (res.status === 401) {
                throw Object.assign(new Error("Unauthorized"), { code: "login" });
            }
            if (!res.ok) {
                throw new Error(`HTTP ${res.status}`);
            }
            return res.json();
        };
        const loadInitialUserPlaylists = async () => {
            if (!isLoggedIn || userState.loading || userState.items.size > 0)
                return;
            userState.loading = true;
            userState.error = null;
            renderUserPlaylists();
            try {
                const data = await fetchUserPlaylistsPage(0);
                mergeUserPlaylists(data?.items);
                userState.total = typeof data?.total === "number" ? data.total : userState.items.size;
                userState.fullyLoaded = userState.items.size >= userState.total;
                userState.page = 1;
                renderUserPlaylists();
                if (!userState.fullyLoaded) {
                    const nextOffset = (data?.offset ?? 0) + (data?.limit ?? data?.items?.length ?? 0);
                    loadRemainingPlaylists(nextOffset);
                }
            }
            catch (error) {
                console.warn("Failed to load user playlists:", error);
                userState.error = error?.code === "login" ? "login" : "error";
            }
            finally {
                userState.loading = false;
                renderUserPlaylists();
            }
        };
        const loadRemainingPlaylists = async (startOffset = 0) => {
            if (userState.fullyLoaded || userState.backgroundLoading)
                return;
            userState.backgroundLoading = true;
            renderUserPlaylists();
            let offset = Math.max(0, startOffset);
            try {
                while (userState.items.size < userState.total) {
                    const data = await fetchUserPlaylistsPage(offset);
                    if (!Array.isArray(data?.items) || !data.items.length)
                        break;
                    mergeUserPlaylists(data.items);
                    offset = (data?.offset ?? offset) + (data?.limit ?? data.items.length);
                    userState.total = typeof data?.total === "number" ? data.total : userState.total;
                    renderUserPlaylists();
                    if (offset >= userState.total)
                        break;
                }
                userState.fullyLoaded = userState.items.size >= userState.total;
            }
            catch (error) {
                console.warn("Failed to load additional playlists:", error);
                userState.backgroundFailed = true;
            }
            finally {
                userState.backgroundLoading = false;
                renderUserPlaylists();
            }
        };
        const handleAuthChange = (loggedIn) => {
            isLoggedIn = !!loggedIn;
            if (userTab) {
                userTab.disabled = !isLoggedIn;
                userTab.setAttribute("aria-selected", "false");
            }
            if (!isLoggedIn) {
                userState.items.clear();
                userState.total = 0;
                userState.loading = false;
                userState.error = null;
                userState.backgroundLoading = false;
                userState.backgroundFailed = false;
                userState.fullyLoaded = false;
                userState.page = 1;
                userState.query = "";
                if (userSearchInput)
                    userSearchInput.value = "";
                if (userSortSelect)
                    userSortSelect.value = "name-asc";
                if (recentTab)
                    toggleTab("recent-panel");
            }
            renderUserPlaylists();
            if (isLoggedIn) {
                loadInitialUserPlaylists();
            }
        };
        if (userSearchInput) {
            userSearchInput.addEventListener("input", (event) => {
                const value = event.target?.value ?? "";
                userState.query = value;
                userState.page = 1;
                renderUserPlaylists();
            });
        }
        if (userSortSelect) {
            userSortSelect.addEventListener("change", (event) => {
                const value = event.target?.value ?? "name-asc";
                userState.sort = value;
                userState.page = 1;
                renderUserPlaylists();
            });
        }
        if (userPrev) {
            userPrev.addEventListener("click", () => {
                if (userState.page > 1) {
                    userState.page -= 1;
                    renderUserPlaylists();
                }
            });
        }
        if (userNext) {
            userNext.addEventListener("click", () => {
                const filtered = getFilteredItems();
                const totalPages = Math.max(1, Math.ceil(filtered.length / userState.pageSize));
                if (userState.page < totalPages) {
                    userState.page += 1;
                    renderUserPlaylists();
                }
            });
        }
        window.addEventListener("vm:auth-state", (event) => {
            handleAuthChange(!!event?.detail?.loggedIn);
        });
        window.addEventListener("vm:auth-error", (event) => {
            const message = event?.detail?.message;
            if (typeof message === "string" && message.trim()) {
                showHomeStatus(message, "error");
            }
        });
        window.addEventListener("resize", syncUserPageSize);
        if (recentTab) {
            recentTab.addEventListener("click", (event) => {
                event.preventDefault();
                toggleTab("recent-panel");
            });
        }
        if (popularTab) {
            popularTab.addEventListener("click", (event) => {
                event.preventDefault();
                toggleTab("popular-panel");
                renderPopular();
            });
        }
        if (userTab) {
            userTab.addEventListener("click", (event) => {
                event.preventDefault();
                if (userTab.disabled) {
                    handleAuthChange(false);
                    return;
                }
                toggleTab("user-panel");
                renderUserPlaylists();
                loadInitialUserPlaylists();
            });
        }
        const sidebarTabs = [recentTab, popularTab, userTab].filter((tab) => tab instanceof HTMLButtonElement);
        bindSidebarTabKeyboard(sidebarTabs);
        toggleTab("recent-panel");
        const btn = document.getElementById("use-link");
        const ta = document.getElementById("playlist-url");
        const gen = document.getElementById("generated-links");
        const apiA = document.getElementById("api-link");
        const pageA = document.getElementById("playlist-page-link");
        let submitting = false;
        const getPlaylistIdFromUrl = (value) => {
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
        };
        const refreshButtonState = () => {
            if (!btn)
                return;
            const id = getPlaylistIdFromUrl(ta?.value.trim() ?? "");
            btn.disabled = submitting || !id;
        };
        if (ta) {
            ta.addEventListener("input", () => {
                ta.setAttribute("aria-invalid", "false");
                showHomeStatus("");
                refreshButtonState();
            });
            refreshButtonState();
        }
        if (btn && ta) {
            const submitPlaylistInput = async () => {
                if (submitting)
                    return;
                const url = ta.value.trim();
                const id = getPlaylistIdFromUrl(url);
                if (!id) {
                    showHomeStatus("Please paste a valid Spotify playlist link first.", "error");
                    ta.setAttribute("aria-invalid", "true");
                    ta.focus();
                    refreshButtonState();
                    return;
                }
                ta.setAttribute("aria-invalid", "false");
                const apiUrl = `/api/playlist?id=${encodeURIComponent(id)}&limit=${PLAYLIST_PAGE_SIZE}`;
                const pageUrl = `/playlist.html?id=${encodeURIComponent(id)}`;
                console.info("Starting playlist load", { playlistId: id });
                if (apiA) {
                    apiA.href = apiUrl;
                    apiA.textContent = `${location.origin}${apiUrl}`;
                }
                if (pageA) {
                    pageA.href = pageUrl;
                    pageA.textContent = pageUrl;
                }
                if (gen)
                    gen.classList.add("visible");
                submitting = true;
                refreshButtonState();
                btn.setAttribute("aria-busy", "true");
                showGlobalLoading("Loading playlist…");
                let response;
                try {
                    response = await fetchWithTimeout(apiUrl, { cache: "no-cache" });
                    if (!response.ok) {
                        throw new Error(`HTTP ${response.status}`);
                    }
                    const payload = await response.json();
                    const cached = readCachedPlaylist(id);
                    storePlaylistChunk(id, payload, cached?.data);
                    renderRecents();
                    location.href = pageUrl;
                }
                catch (e) {
                    hideGlobalLoading();
                    console.error("Playlist could not be loaded", e);
                    const apiError = await readApiError(response);
                    const fallback = e instanceof Error
                        ? e.message
                        : "Playlist could not be loaded. Please try again later.";
                    const msg = getPlaylistLoadErrorMessage(response, apiError, fallback, "open");
                    showHomeStatus(msg, "error");
                }
                finally {
                    submitting = false;
                    btn.removeAttribute("aria-busy");
                    refreshButtonState();
                }
            };
            ta.addEventListener("keydown", (event) => {
                if (event.key !== "Enter") {
                    return;
                }
                event.preventDefault();
                submitPlaylistInput();
            });
            btn.addEventListener("click", submitPlaylistInput);
        }
    }
    else if (isPlaylistPage) {
        const urlParams = new URLSearchParams(location.search);
        const id = urlParams.get("id");
        if (!id) {
            loadPlaylist();
            return;
        }
        loadPlaylist(id, PLAYLIST_PAGE_SIZE);
    }
});
async function loadPlaylistAndNavigate(id, options = {}) {
    if (!id)
        return;
    const pageUrl = `/playlist.html?id=${encodeURIComponent(id)}`;
    showGlobalLoading(options.title ? `Loading ${options.title}…` : "Loading playlist…");
    let response;
    try {
        const query = `/api/playlist?id=${encodeURIComponent(id)}&limit=${PLAYLIST_PAGE_SIZE}`;
        response = await fetchWithTimeout(query, { cache: "no-cache" });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        const payload = await response.json();
        const cached = readCachedPlaylist(id);
        storePlaylistChunk(id, payload, cached?.data);
        renderRecents();
        location.href = pageUrl;
    }
    catch (e) {
        hideGlobalLoading();
        const apiError = await readApiError(response);
        const msg = getPlaylistLoadErrorMessage(response, apiError, e instanceof Error ? e.message : String(e), "open");
        showHomeStatus("Playlist could not be opened: " + msg, "error");
    }
}

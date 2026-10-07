import { readApiError } from "./api-errors.js";
import { loadCustomVendors } from "./vendors.js";
import { renderItems, renderLoading, renderMessage, renderArtist, renderAlbum, renderProviders, element } from "./quicksearch-view.js";

export function initQuicksearch(trigger) {
    if (!(trigger instanceof HTMLButtonElement) || document.getElementById("quicksearch-dialog")) return;
    const stylesheet = element("link");
    stylesheet.rel = "stylesheet";
    stylesheet.href = "/styles/quicksearch.css";
    document.head.append(stylesheet);
    const dialog = element("dialog", "quicksearch-dialog");
    dialog.id = "quicksearch-dialog";
    dialog.setAttribute("aria-labelledby", "qs-title");
    dialog.innerHTML = `
        <div class="qs-header">
            <h2 id="qs-title">Quicksearch</h2>
            <button type="button" class="qs-close" aria-label="Close Quicksearch">✕</button>
        </div>
        <div class="qs-search-pane">
            <label class="qs-search-label" for="qs-input">Find artists, albums or songs</label>
            <input id="qs-input" class="qs-input" name="quicksearch" type="search" maxlength="160"
                placeholder="Artist, album or song…" autocomplete="off" spellcheck="false" aria-describedby="qs-status" />
            <fieldset class="qs-filters">
                <legend class="sr-only">Search type</legend>
                <label><input type="radio" name="qs-type" value="all" checked /><span>All</span></label>
                <label><input type="radio" name="qs-type" value="artists" /><span>Artists</span></label>
                <label><input type="radio" name="qs-type" value="albums" /><span>Albums</span></label>
                <label><input type="radio" name="qs-type" value="songs" /><span>Songs</span></label>
            </fieldset>
            <p id="qs-status" class="qs-status" role="status" aria-live="polite"></p>
            <ul class="qs-results" aria-label="Search results"></ul>
            <p class="qs-footer">Search powered by Discogs <span>↑ ↓ to move · Esc to close</span></p>
        </div>
        <div class="qs-detail-pane" hidden>
            <button type="button" class="qs-back">← Back to results</button>
            <p class="qs-detail-status qs-status" role="status" aria-live="polite"></p>
            <div class="qs-detail-scroll"><div class="qs-detail-content"></div></div>
        </div>`;
    document.body.append(dialog);
    const input = dialog.querySelector("#qs-input");
    const searchPane = dialog.querySelector(".qs-search-pane");
    const detailPane = dialog.querySelector(".qs-detail-pane");
    const results = dialog.querySelector(".qs-results");
    const status = dialog.querySelector("#qs-status");
    const detailStatus = dialog.querySelector(".qs-detail-status");
    const detailScroll = dialog.querySelector(".qs-detail-scroll");
    const content = dialog.querySelector(".qs-detail-content");
    const back = dialog.querySelector(".qs-back");
    let timer;
    let request;
    let generation = 0;
    let items = [];
    let frames = [{ kind: "search", scroll: 0 }];
    let vendorsRequest;
    let vendorError = "";
    const current = () => frames[frames.length - 1];

    function cancelRequest() {
        clearTimeout(timer);
        request?.abort();
        generation++;
    }

    async function get(path, signal) {
        try {
            const response = await fetch(path, { signal, credentials: "include" });
            if (!response.ok) {
                const error = await readApiError(response);
                return { ok: false, message: error?.message || "The search could not finish. Please try again." };
            }
            return { ok: true, data: await response.json() };
        } catch (error) {
            if (error.name === "AbortError") return { cancelled: true };
            console.error("Quicksearch request failed", error);
            return { ok: false, message: "The search could not connect. Check your connection and try again." };
        }
    }

    function showSearch() {
        searchPane.hidden = false;
        detailPane.hidden = true;
        results.removeAttribute("aria-busy");
        if (items.length) renderItems(results, items, select);
        else renderMessage(results, "Find your next record", "Enter at least two characters. Song searches find releases that contain the song.");
    }

    function scheduleSearch() {
        cancelRequest();
        items = [];
        results.scrollTop = 0;
        frames = [{ kind: "search", scroll: 0 }];
        const query = input.value.trim();
        if (query.length < 2) {
            status.textContent = "Enter at least two characters.";
            showSearch();
            return;
        }
        status.textContent = "Searching Discogs…";
        results.setAttribute("aria-busy", "true");
        renderLoading(results);
        timer = setTimeout(search, 300);
    }

    async function search() {
        cancelRequest();
        const version = generation;
        request = new AbortController();
        const query = input.value.trim();
        const type = dialog.querySelector('input[name="qs-type"]:checked').value;
        status.textContent = "Searching Discogs…";
        results.setAttribute("aria-busy", "true");
        renderLoading(results);
        const response = await get(`/api/quicksearch?${new URLSearchParams({ q: query, type })}`, request.signal);
        if (version !== generation || response.cancelled || !dialog.open) return;
        results.removeAttribute("aria-busy");
        if (!response.ok) {
            status.textContent = response.message;
            renderMessage(results, "Search unavailable", response.message, () => {
                input.focus({ preventScroll: true });
                search();
            });
            return;
        }
        items = response.data.items;
        status.textContent = `${items.length} ${items.length === 1 ? "result" : "results"}${type === "songs" ? " · releases containing this song" : ""}`;
        if (items.length) renderItems(results, items, select);
        else renderMessage(results, "No results", "Try another spelling, an artist name or a different search type.");
    }

    function savePosition(item) {
        current().scroll = current().kind === "search" ? results.scrollTop : detailScroll.scrollTop;
        current().focusKey = `${item.kind}:${item.id}`;
    }

    async function select(item) {
        savePosition(item);
        const frame = { kind: item.kind === "artist" ? "artist" : "album", item, scroll: 0 };
        frames.push(frame);
        searchPane.hidden = true;
        detailPane.hidden = false;
        back.textContent = frames[frames.length - 2].kind === "search" ? "← Back to results" : "← Back";
        detailScroll.scrollTop = 0;
        await loadFrame(frame);
    }

    async function loadFrame(frame) {
        cancelRequest();
        const version = generation;
        request = new AbortController();
        detailStatus.textContent = frame.kind === "artist" ? "Loading artist releases…" : "Checking Discogs…";
        content.setAttribute("aria-busy", "true");
        renderLoading(content);
        back.focus({ preventScroll: true });
        if (frame.kind === "album") loadVendors();
        const params = new URLSearchParams({ id: frame.item.id });
        if (frame.kind === "album") params.set("kind", frame.item.kind);
        const response = await get(`/api/quicksearch/${frame.kind}?${params}`, request.signal);
        if (version !== generation || response.cancelled || !dialog.open) return;
        if (!response.ok) {
            content.removeAttribute("aria-busy");
            detailStatus.textContent = response.message;
            renderMessage(content, "Item unavailable", response.message, () => loadFrame(frame));
            return;
        }
        frame.data = response.data;
        renderFrame(frame).focus();
    }

    function loadVendors() {
        if (vendorsRequest) return;
        vendorsRequest = loadCustomVendors().then(loaded => {
            vendorError = loaded ? "" : "Custom shop settings could not load. Default shop links are shown.";
            if (!loaded) vendorsRequest = null;
            const frame = current();
            if (!dialog.open || frame.kind !== "album" || !frame.data || content.hasAttribute("aria-busy")) return;
            detailStatus.textContent = vendorError;
            if (!loaded) return;
            const providers = content.querySelector(".qs-providers");
            const focused = providers.contains(document.activeElement) ? document.activeElement.dataset.key : null;
            const scroll = detailScroll.scrollTop;
            renderProviders(providers, frame.data);
            if (focused) restoreFocus(providers, focused, providers.querySelector("a") || back);
            detailScroll.scrollTop = scroll;
        });
    }

    function restoreFocus(scope, key, fallback) {
        const focused = [...scope.querySelectorAll("[data-key]")].find(node => node.dataset.key === key);
        (focused || fallback).focus({ preventScroll: true });
    }

    function renderFrame(frame) {
        content.removeAttribute("aria-busy");
        detailStatus.textContent = "";
        if (frame.kind === "artist") {
            detailStatus.textContent = `${frame.data.albums.length} releases loaded`;
            return renderArtist(content, frame.data, select, loadMore);
        }
        detailStatus.textContent = vendorError;
        return renderAlbum(content, frame.data, select, frame.item.songQuery);
    }

    async function loadMore() {
        const frame = current();
        const more = content.querySelector(".qs-load-more");
        if (frame.kind !== "artist" || frame.data.page >= frame.data.pages || more?.getAttribute("aria-disabled") === "true") return;
        cancelRequest();
        const version = generation;
        request = new AbortController();
        const scroll = detailScroll.scrollTop;
        const count = frame.data.albums.length;
        more.setAttribute("aria-disabled", "true");
        more.textContent = "Loading releases…";
        detailStatus.textContent = "Loading more releases…";
        detailScroll.scrollTop = scroll;
        const response = await get(`/api/quicksearch/artist?${new URLSearchParams({ id: frame.item.id, page: frame.data.page + 1 })}`, request.signal);
        if (version !== generation || response.cancelled || !dialog.open) return;
        if (response.ok) {
            const known = new Set(frame.data.albums.map(item => `${item.kind}:${item.id}`));
            frame.data.albums.push(...response.data.albums.filter(item => !known.has(`${item.kind}:${item.id}`)));
            frame.data.page = response.data.page;
            frame.data.pages = response.data.pages;
        }
        renderFrame(frame);
        detailScroll.scrollTop = scroll;
        if (response.ok) {
            (content.querySelectorAll(".qs-result")[count] || content.querySelector(".qs-load-more") || back).focus();
        } else {
            detailStatus.textContent = response.message;
            content.querySelector(".qs-load-more")?.focus();
        }
    }

    back.addEventListener("click", () => {
        cancelRequest();
        frames.pop();
        const frame = current();
        if (frame.kind === "search") {
            showSearch();
            results.scrollTop = frame.scroll;
        } else if (frame.data) {
            renderFrame(frame);
            detailScroll.scrollTop = frame.scroll;
        } else {
            loadFrame(frame);
        }
        back.textContent = frames.length < 3 ? "← Back to results" : "← Back";
        const scope = frame.kind === "search" ? results : content;
        restoreFocus(scope, frame.focusKey, frame.kind === "search" ? input : back);
    });

    dialog.addEventListener("keydown", event => {
        if (event.key === "Escape") {
            event.preventDefault();
            event.stopPropagation();
            dialog.close();
            return;
        }
        const list = event.target.closest(".qs-results, .qs-artist-albums");
        if (event.target === input && event.key === "ArrowDown") {
            const first = results.querySelector(".qs-result");
            if (first) { event.preventDefault(); first.focus(); }
        } else if (list && ["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
            const buttons = [...list.querySelectorAll(".qs-result")];
            const index = buttons.indexOf(document.activeElement);
            if (index < 0) return;
            event.preventDefault();
            if (index === 0 && event.key === "ArrowUp" && list === results) { input.focus(); return; }
            const next = event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1 :
                Math.max(0, Math.min(buttons.length - 1, index + (event.key === "ArrowDown" ? 1 : -1)));
            buttons[next].focus();
        }
    });
    input.addEventListener("input", scheduleSearch);
    dialog.querySelector(".qs-filters").addEventListener("change", scheduleSearch);
    dialog.querySelector(".qs-close").addEventListener("click", () => dialog.close());
    dialog.addEventListener("click", event => {
        if (event.target !== dialog) return;
        const bounds = dialog.getBoundingClientRect();
        if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dialog.close();
    });
    dialog.addEventListener("close", () => {
        cancelRequest();
        document.body.classList.remove("quicksearch-open");
        trigger.focus();
    });
    trigger.addEventListener("click", () => {
        frames = [{ kind: "search", scroll: 0 }];
        showSearch();
        dialog.showModal();
        document.body.classList.add("quicksearch-open");
        input.focus();
        if (input.value.trim().length >= 2 && !items.length) scheduleSearch();
    });
}

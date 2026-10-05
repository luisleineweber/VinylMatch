import { initQuicksearch } from "/dist/common/quicksearch.js";
import { resetVendors } from "/dist/common/vendors.js";

const item = { id: 7, kind: "master", title: "Example Album", artist: "Example Artist" };
const album = {
    ...item, year: 2001, image: null, url: "https://www.discogs.com/master/7",
    artists: [{ id: 4, name: "Example Artist" }], offers: 5, vinyl: null,
    marketplaceUrl: "https://www.discogs.com/sell/list?master_id=7&format=Vinyl",
    tracks: Array.from({ length: 40 }, (_, index) => ({ position: String(index + 1), title: `Track ${index}`, duration: "4:00" })),
};
const artist = {
    id: 4, name: "Example Artist", image: null, url: "https://www.discogs.com/artist/4",
    albums: [item], page: 1, pages: 2, total: 2,
};
const json = (data, status = 200) => new Response(JSON.stringify(data), { status });
const assert = (condition, message) => { if (!condition) throw new Error(message); };

function deferred() {
    let resolve;
    const promise = new Promise(done => { resolve = done; });
    return { promise, resolve };
}

async function until(check) {
    const deadline = performance.now() + 3000;
    while (!check()) {
        if (performance.now() > deadline) throw new Error("Timed out waiting for the UI");
        await new Promise(resolve => setTimeout(resolve, 10));
    }
}

function defaultResponse(url) {
    const path = String(url);
    if (path.startsWith("/api/quicksearch/album?")) return json(album);
    if (path.startsWith("/api/quicksearch/artist?")) return json(artist);
    if (path.startsWith("/api/quicksearch?")) return json({ items: [item] });
    if (path === "/api/config/vendors") return json({ vendors: [] });
    throw new Error(`Unexpected request: ${path}`);
}

async function withDialog(handler, check) {
    resetVendors();
    const nativeFetch = window.fetch;
    window.fetch = (url, options) => Promise.resolve(handler(String(url), options));
    const trigger = document.createElement("button");
    trigger.textContent = "Open Quicksearch";
    document.body.append(trigger);
    initQuicksearch(trigger);
    const dialog = document.getElementById("quicksearch-dialog");
    trigger.click();
    try {
        await check(dialog);
    } finally {
        if (dialog.open) {
            const closed = new Promise(resolve => dialog.addEventListener("close", resolve, { once: true }));
            dialog.close();
            await closed;
        }
        dialog.remove();
        trigger.remove();
        document.querySelector('link[href="/styles/quicksearch.css"]').remove();
        window.fetch = nativeFetch;
    }
}

function enterQuery(dialog, query = "Example") {
    const input = dialog.querySelector("#qs-input");
    input.value = query;
    input.dispatchEvent(new Event("input", { bubbles: true }));
}

async function openAlbum(dialog) {
    enterQuery(dialog);
    await until(() => dialog.querySelector(".qs-result"));
    dialog.querySelector(".qs-result").click();
    await until(() => dialog.querySelector(".qs-album-artists"));
}

const tests = [
    ["Retry keeps focus inside the dialog", async () => {
        let searches = 0;
        const retry = deferred();
        await withDialog(url => {
            if (url.startsWith("/api/quicksearch?")) {
                return ++searches === 1 ? json({ error: { message: "Please try again." } }, 429) : retry.promise;
            }
            return defaultResponse(url);
        }, async dialog => {
            enterQuery(dialog);
            await until(() => dialog.querySelector(".qs-message button"));
            const button = dialog.querySelector(".qs-message button");
            button.focus();
            button.click();
            assert(document.activeElement === dialog.querySelector("#qs-input"), "Retry lost keyboard focus");
            retry.resolve(json({ items: [item] }));
            await until(() => dialog.querySelector(".qs-result"));
            assert(dialog.contains(document.activeElement), "The result left focus outside the dialog");
        });
    }],
    ["Slow shop settings do not hold back album details or move focus", async () => {
        const vendors = deferred();
        await withDialog(url => url === "/api/config/vendors" ? vendors.promise : defaultResponse(url), async dialog => {
            await openAlbum(dialog);
            const track = dialog.querySelector(".qs-tracks li");
            const marketplace = dialog.querySelector(".qs-providers a");
            marketplace.focus();
            const scroll = dialog.querySelector(".qs-detail-scroll");
            scroll.scrollTop = 100;
            const position = scroll.scrollTop;
            vendors.resolve(json({ vendors: [{ id: "test-shop", name: "Test Shop", urlTemplate: "https://shop.example/search?q={query}" }] }));
            await until(() => dialog.querySelector(".qs-providers").textContent.includes("Test Shop"));
            assert(dialog.querySelector(".qs-tracks li") === track, "Shop settings replaced the album view");
            assert(document.activeElement.href === marketplace.href, "Shop settings lost link focus");
            assert(scroll.scrollTop === position, "Shop settings moved the scroll position");
        });
    }],
    ["Back restores the artist button and album scroll position", async () => {
        await withDialog(defaultResponse, async dialog => {
            await openAlbum(dialog);
            const scroll = dialog.querySelector(".qs-detail-scroll");
            scroll.scrollTop = 100;
            const position = scroll.scrollTop;
            dialog.querySelector(".qs-text-button").click();
            await until(() => dialog.querySelector(".qs-artist-albums"));
            dialog.querySelector(".qs-back").click();
            assert(document.activeElement === dialog.querySelector(".qs-text-button"), "Back did not restore the artist button");
            assert(scroll.scrollTop === position, "Back did not restore album scroll");
        });
    }],
    ["Late search responses cannot replace the latest result", async () => {
        const requests = [];
        await withDialog(url => {
            if (!url.startsWith("/api/quicksearch?")) return defaultResponse(url);
            const request = deferred();
            requests.push(request);
            return request.promise;
        }, async dialog => {
            enterQuery(dialog, "First");
            await until(() => requests.length === 1);
            enterQuery(dialog, "Second");
            await until(() => requests.length === 2);
            requests[1].resolve(json({ items: [{ ...item, title: "Second result" }] }));
            await until(() => dialog.querySelector(".qs-result-title")?.textContent === "Second result");
            requests[0].resolve(json({ items: [{ ...item, title: "First result" }] }));
            await new Promise(resolve => setTimeout(resolve, 0));
            assert(dialog.querySelector(".qs-result-title").textContent === "Second result", "A stale response replaced the latest result");
        });
    }],
    ["Closing the dialog cancels visible updates and restores the trigger", async () => {
        const request = deferred();
        let started = false;
        await withDialog(url => {
            if (!url.startsWith("/api/quicksearch?")) return defaultResponse(url);
            started = true;
            return request.promise;
        }, async dialog => {
            enterQuery(dialog);
            await until(() => started);
            const closed = new Promise(resolve => dialog.addEventListener("close", resolve, { once: true }));
            dialog.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
            await closed;
            assert(document.activeElement.textContent === "Open Quicksearch", "Close did not restore trigger focus");
            request.resolve(json({ items: [item] }));
            await new Promise(resolve => setTimeout(resolve, 0));
            assert(!dialog.querySelector(".qs-result"), "A closed dialog accepted a late response");
        });
    }],
    ["Loading more keeps button focus and prevents duplicate requests", async () => {
        const page = deferred();
        let pages = 0;
        await withDialog(url => {
            if (url.includes("/artist?") && url.includes("page=2")) { pages++; return page.promise; }
            return defaultResponse(url);
        }, async dialog => {
            await openAlbum(dialog);
            dialog.querySelector(".qs-text-button").click();
            await until(() => dialog.querySelector(".qs-load-more"));
            const more = dialog.querySelector(".qs-load-more");
            more.focus();
            more.click();
            assert(document.activeElement === more, "Loading more removed the focused button");
            more.click();
            assert(pages === 1, "Loading more sent duplicate page requests");
            page.resolve(json({ ...artist, albums: [{ ...item, id: 8 }], page: 2 }));
            await until(() => dialog.querySelector('[data-key="master:8"]'));
            assert(document.activeElement.dataset.key === "master:8", "Loading more did not focus the new result");
        });
    }],
    ["Shop setting failures stay visible while album details remain usable", async () => {
        await withDialog(url => url === "/api/config/vendors" ? json({}, 503) : defaultResponse(url), async dialog => {
            await openAlbum(dialog);
            await until(() => dialog.querySelector(".qs-detail-status").textContent.includes("shop settings could not load"));
            assert(dialog.querySelector(".qs-tracks"), "Shop failure hid album tracks");
            assert(dialog.querySelector(".qs-providers a"), "Shop failure hid the marketplace link");
            const close = dialog.querySelector(".qs-close").getBoundingClientRect();
            assert(close.top >= 0 && close.bottom <= innerHeight, "Shop failure moved Close outside the viewport");
        });
    }],
];

window.quicksearchTestResults = [];
for (const [name, run] of tests) {
    const row = document.createElement("li");
    document.getElementById("test-results").append(row);
    try {
        await run();
        row.textContent = `PASS: ${name}`;
        window.quicksearchTestResults.push({ name, passed: true });
    } catch (error) {
        row.textContent = `FAIL: ${name}: ${error.message}`;
        window.quicksearchTestResults.push({ name, passed: false, error: error.message });
    }
}
const failures = window.quicksearchTestResults.filter(result => !result.passed).length;
document.getElementById("summary").textContent = `${tests.length - failures}/${tests.length} tests passed`;
document.body.dataset.tests = failures ? "failed" : "passed";

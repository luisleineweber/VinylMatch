import test from "node:test";
import assert from "node:assert/strict";

import {
    fetchWithTimeout,
    PLAYLIST_REQUEST_TIMEOUT_MS,
} from "../../main/frontend/dist/common/api-errors.js";

test("playlist requests have a bounded timeout and expose a user-facing error", async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = (_input, init) => new Promise((_resolve, reject) => {
        init.signal.addEventListener("abort", () => {
            reject(new DOMException("Aborted", "AbortError"));
        }, { once: true });
    });

    try {
        await assert.rejects(
            fetchWithTimeout("/api/playlist?id=stalled", {}, 10),
            (error) => error?.code === "request_timeout"
                && error.message.includes("timed out")
                && error.timeoutMs === 10,
        );
    }
    finally {
        globalThis.fetch = originalFetch;
    }

    assert.equal(PLAYLIST_REQUEST_TIMEOUT_MS, 30_000);
});

test("the timeout includes a response body that stalls after headers", async (t) => {
    let bodyController;
    t.mock.method(globalThis, "fetch", async (_input, { signal }) => new Response(new ReadableStream({
        start(controller) {
            bodyController = controller;
            signal.addEventListener("abort", () => controller.error(signal.reason), { once: true });
        },
    }), { headers: { "Content-Type": "application/json" } }));

    let watchdog;
    try {
        await assert.rejects(Promise.race([
            fetchWithTimeout("/api/playlist?id=stalled-body", {}, 10).then((response) => response.json()),
            new Promise((_resolve, reject) => {
                watchdog = setTimeout(() => reject(new Error("Response body exceeded the request timeout")), 200);
            }),
        ]), (error) => error?.code === "request_timeout" && error.timeoutMs === 10);
    }
    finally {
        clearTimeout(watchdog);
        bodyController.error(new DOMException("Test finished", "AbortError"));
    }
});

test("completed responses keep their status, headers and readable body", async (t) => {
    t.mock.method(globalThis, "fetch", async () => Response.json({ saved: true }, { status: 201 }));
    const response = await fetchWithTimeout("/api/discogs/curation/save", {}, 100);
    assert.equal(response.status, 201);
    assert.equal(response.headers.get("content-type"), "application/json");
    assert.deepEqual(await response.clone().json(), { saved: true });
    assert.deepEqual(await response.json(), { saved: true });
});

test("caller cancellation during body reading keeps the caller error", async (t) => {
    const caller = new AbortController();
    const reason = new DOMException("Cancelled by caller", "AbortError");
    let bodyController;
    t.mock.method(globalThis, "fetch", async (_input, { signal }) => new Response(new ReadableStream({
        start(controller) {
            bodyController = controller;
            signal.addEventListener("abort", () => controller.error(signal.reason), { once: true });
            setImmediate(() => caller.abort(reason));
        },
    })));
    let watchdog;
    try {
        await assert.rejects(Promise.race([
            fetchWithTimeout("/api/playlist", { signal: caller.signal }, 100).then((response) => response.text()),
            new Promise((_resolve, reject) => {
                watchdog = setTimeout(() => reject(new Error("Caller cancellation was ignored")), 200);
            }),
        ]), (error) => error.name === "AbortError" && error.message === reason.message);
    }
    finally {
        clearTimeout(watchdog);
        bodyController.error(new DOMException("Test finished", "AbortError"));
    }
});

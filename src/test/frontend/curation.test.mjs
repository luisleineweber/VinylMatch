import test from "node:test";
import assert from "node:assert/strict";

for (const initialVersion of [0, 7]) {
    test(`curation loads version ${initialVersion} and keeps it after queue updates`, async (t) => {
        const nodes = new Map();
        for (const id of ["curation-start", "curation-next", "curation-manual-link-form", "curation-manual-link-save", "curation-manual-link-url", "curation-status"]) {
            nodes.set(`#${id}`, {
                textContent: "Save", value: "", disabled: false, listeners: {},
                classList: { add() {}, remove() {} },
                setCustomValidity() {},
                addEventListener(type, listener) { this.listeners[type] = listener; },
            });
        }
        const originalWindow = globalThis.window;
        const originalDocument = globalThis.document;
        t.after(() => {
            globalThis.window = originalWindow;
            globalThis.document = originalDocument;
        });
        globalThis.window = {
            location: { origin: "http://127.0.0.1:8888" }, addEventListener() {},
        };
        globalThis.document = {
            getElementById(id) { return nodes.get(`#${id}`); },
        };
        t.mock.method(globalThis, "setTimeout", () => 0);
        t.mock.method(globalThis, "clearTimeout", () => {});

        let version = initialVersion;
        const saves = [];
        let candidateLoads = 0;
        t.mock.method(globalThis, "fetch", async (url, init) => {
            if (url.endsWith("/candidates")) {
                candidateLoads++;
                return Response.json({ candidates: [], curationVersion: version });
            }
            const payload = JSON.parse(init.body);
            saves.push(payload.expectedVersion);
            if (payload.expectedVersion !== version) {
                return Response.json({ error: { message: "Version conflict" } }, { status: 409 });
            }
            return Response.json({ entry: { version: ++version } });
        });
        const { initCurationPanel } = await import("../../main/frontend/dist/curation.js");
        let savedCount = 0;
        const panel = await initCurationPanel({
            container: { dataset: {}, querySelector(selector) { return nodes.get(selector); } },
            buildQueue: () => [{ artist: "Artist", album: "Album", releaseYear: 2024 }],
            onCandidateSaved() { savedCount++; panel.refreshQueue(); },
        });

        await nodes.get("#curation-start").listeners.click();
        assert.equal(candidateLoads, 1);

        for (let count = 1; count <= 2; count++) {
            nodes.get("#curation-manual-link-url").value = `https://www.discogs.com/release/${count}`;
            await nodes.get("#curation-manual-link-form").listeners.submit({ preventDefault() {} });
            assert.equal(savedCount, count, nodes.get("#curation-status").textContent);
        }
        assert.deepEqual(saves, [initialVersion, initialVersion + 1]);
    });
}

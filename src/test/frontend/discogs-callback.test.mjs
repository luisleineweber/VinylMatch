import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";

const script = readFileSync(new URL("../../main/frontend/dist/discogs-callback.js", import.meta.url), "utf8");

for (const success of [false, true]) {
    test(`Discogs callback sends ${success ? "success" : "failure"} to its opener`, () => {
        const messages = [];
        const timers = [];
        const window = {
            closed: false, location: { origin: "https://vinyl.example", href: "" },
            opener: { postMessage(payload, origin) { messages.push({ payload: JSON.parse(JSON.stringify(payload)), origin }); } },
            close() { this.closed = true; },
        };
        runInNewContext(script, {
            document: { querySelector: () => ({ dataset: { callbackSuccess: String(success), callbackMessage: "<Denied>" } }) },
            window, setTimeout(callback, delay) { timers.push({ callback, delay }); },
        });
        assert.deepEqual(messages, [{
            payload: { type: "discogs-auth-callback", success, ...(!success ? { message: "<Denied>" } : {}) },
            origin: "https://vinyl.example",
        }]);
        assert.equal(timers.length, success ? 1 : 0);
        if (success) {
            assert.equal(timers[0].delay, 600);
            timers[0].callback();
            assert.equal(window.closed, true);
            window.close = () => {};
            window.closed = false;
            timers[0].callback();
            assert.equal(window.location.href, "/playlist.html");
        }
    });
}

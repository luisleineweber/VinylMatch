import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";

const script = readFileSync(new URL("../../main/frontend/dist/oauth-callback.js", import.meta.url), "utf8");

for (const provider of ["spotify", "discogs"]) {
    for (const success of [false, true]) {
        test(`${provider} callback sends its result and returns without a delay`, () => {
            const messages = [];
            const listeners = {};
            const target = provider === "spotify" ? "/" : "/playlist.html";
            const payload = { type: `${provider}-auth-callback`, success, code: "callback_result", message: "<Result>" };
            const window = {
                closed: false,
                location: { origin: "https://vinyl.example", href: "", replace(path) { this.href = path; } },
                opener: { postMessage(value, origin) { messages.push({ payload: JSON.parse(JSON.stringify(value)), origin }); } },
                close() { this.closed = true; },
            };
            runInNewContext(script, {
                document: {
                    documentElement: { dataset: {
                        callbackSuccess: String(success), callbackType: payload.type,
                        callbackCode: payload.code, callbackMessage: payload.message, callbackTarget: target,
                    } },
                    addEventListener(type, listener) { listeners[type] = listener; },
                    getElementById() { return { addEventListener(type, listener) { listeners[type] = listener; } }; },
                },
                window,
                setTimeout() { throw new Error("Login must return without a fixed delay"); },
            });
            assert.deepEqual(messages, [{ payload, origin: "https://vinyl.example" }]);
            assert.equal(window.closed, success);

            listeners.DOMContentLoaded();
            window.close = () => {};
            window.closed = false;
            let prevented = false;
            listeners.click({ preventDefault() { prevented = true; } });
            assert.equal(prevented, true);
            assert.equal(window.location.href, target);
        });
    }
}

test("successful direct login replaces the callback in browser history", () => {
    const window = {
        closed: false, opener: null,
        location: { href: "", replace(path) { this.href = path; } },
        close() {},
    };
    runInNewContext(script, {
        document: { documentElement: { dataset: { callbackSuccess: "true", callbackTarget: "/" } }, addEventListener() {} },
        window,
    });
    assert.equal(window.location.href, "/");
});

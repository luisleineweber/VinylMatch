import test from "node:test";
import assert from "node:assert/strict";

import {
    buildSpotifyAuthCallbackPayload,
    closePopupOrNavigate,
    initSpotifyAuthCallback,
} from "../../main/frontend/dist/spotify-callback.js";

test("builds the callback payload from escaped data attributes", () => {
    const payload = buildSpotifyAuthCallbackPayload({
        dataset: {
            authSuccess: "true",
            authCode: "spotify_connected",
            authMessage: "Spotify is connected.",
        },
    });

    assert.deepEqual(payload, {
        type: "spotify-auth-callback",
        success: true,
        code: "spotify_connected",
        message: "Spotify is connected.",
    });
});

test("uses safe fallback values when callback data is missing", () => {
    assert.deepEqual(buildSpotifyAuthCallbackPayload({ dataset: {} }), {
        type: "spotify-auth-callback",
        success: false,
        code: "spotify_callback_failed",
        message: "The Spotify login failed unexpectedly.",
    });
});

test("posts same-origin callback data and wires the manual close fallback", () => {
    const listeners = {};
    const root = { dataset: { authSuccess: "false", authCode: "cancelled", authMessage: "Cancelled" } };
    const action = {
        addEventListener(type, listener) {
            listeners[type] = listener;
        },
    };
    const messages = [];
    const windowRef = {
        closed: false,
        location: { origin: "http://127.0.0.1:8888", href: "" },
        opener: {
            closed: false,
            postMessage(payload, origin) {
                messages.push({ payload, origin });
            },
        },
        close() {
            this.closed = true;
            this.location.href = "/closed";
        },
        setTimeout() {},
    };
    const documentRef = {
        getElementById(id) {
            return id === "spotify-auth-callback" ? root : action;
        },
    };

    const payload = initSpotifyAuthCallback({ documentRef, windowRef });

    assert.equal(payload.success, false);
    assert.deepEqual(messages, [{ payload, origin: "http://127.0.0.1:8888" }]);
    assert.equal(typeof listeners.click, "function");

    let prevented = false;
    listeners.click({ preventDefault() { prevented = true; } });
    assert.equal(prevented, true);
    assert.equal(windowRef.location.href, "/closed");
});

test("navigates when the browser refuses to close the popup", () => {
    const windowRef = {
        closed: false,
        location: { href: "" },
        close() {},
    };

    closePopupOrNavigate(windowRef, "/home.html");

    assert.equal(windowRef.location.href, "/home.html");
});

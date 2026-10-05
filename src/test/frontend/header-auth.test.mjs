import test from "node:test";
import assert from "node:assert/strict";

import {
    isTrustedSpotifyAuthCallbackMessage,
    spotifyAuthPollFailure
} from "../../main/frontend/dist/common/header.js";

test("Spotify callback messages require the expected origin, popup and type", () => {
    const popup = {};
    const trusted = {
        origin: "https://vinyl.example",
        source: popup,
        data: { type: "spotify-auth-callback", success: true }
    };

    assert.equal(isTrustedSpotifyAuthCallbackMessage(trusted, "https://vinyl.example", popup), true);
    assert.equal(isTrustedSpotifyAuthCallbackMessage({ ...trusted, origin: "https://evil.example" }, "https://vinyl.example", popup), false);
    assert.equal(isTrustedSpotifyAuthCallbackMessage({ ...trusted, source: {} }, "https://vinyl.example", popup), false);
    assert.equal(isTrustedSpotifyAuthCallbackMessage({ ...trusted, data: { type: "other" } }, "https://vinyl.example", popup), false);
});

test("Spotify polling distinguishes a closed popup and timeout", () => {
    assert.deepEqual(spotifyAuthPollFailure(true, 1_000), {
        code: "spotify_popup_closed",
        message: "The Spotify login window was closed before login completed. Please try again."
    });
    assert.deepEqual(spotifyAuthPollFailure(false, 120_000), {
        code: "spotify_login_timeout",
        message: "Spotify login timed out. Start the login again."
    });
    assert.equal(spotifyAuthPollFailure(false, 1_000), null);
});

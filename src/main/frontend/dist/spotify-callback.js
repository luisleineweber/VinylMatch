export function buildSpotifyAuthCallbackPayload(root) {
    const dataset = root?.dataset ?? {};
    return {
        type: "spotify-auth-callback",
        success: dataset.authSuccess === "true",
        code: dataset.authCode || "spotify_callback_failed",
        message: dataset.authMessage || "The Spotify login failed unexpectedly.",
    };
}

export function closePopupOrNavigate(windowRef, fallbackPath = "/") {
    windowRef.close();
    if (!windowRef.closed) {
        windowRef.location.href = fallbackPath;
    }
}

export function initSpotifyAuthCallback({
    documentRef = globalThis.document,
    windowRef = globalThis.window,
} = {}) {
    const callback = documentRef?.getElementById("spotify-auth-callback");
    if (!callback || !windowRef) return null;

    const payload = buildSpotifyAuthCallbackPayload(callback);
    if (windowRef.opener && !windowRef.opener.closed) {
        windowRef.opener.postMessage(payload, windowRef.location.origin);
    }

    const action = documentRef.getElementById("oauth-callback-action");
    if (action) {
        action.addEventListener("click", (event) => {
            if (!windowRef.opener) return;
            event.preventDefault();
            closePopupOrNavigate(windowRef);
        });
    }

    if (payload.success) {
        windowRef.setTimeout(() => closePopupOrNavigate(windowRef), 1200);
    }

    return payload;
}

if (typeof document !== "undefined" && typeof window !== "undefined") {
    initSpotifyAuthCallback();
}

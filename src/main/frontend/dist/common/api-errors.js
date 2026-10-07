export async function readApiError(response) {
    if (!response) {
        return null;
    }
    const contentType = response.headers.get("content-type") || "";
    if (!contentType.includes("application/json")) {
        return null;
    }
    try {
        const payload = await response.clone().json();
        const error = payload?.error;
        if (!error || typeof error !== "object") {
            return null;
        }
        return {
            code: typeof error.code === "string" ? error.code : null,
            message: typeof error.message === "string" ? error.message : null,
            status: typeof error.status === "number" ? error.status : response.status,
        };
    }
    catch (_a) {
        return null;
    }
}

export const PLAYLIST_REQUEST_TIMEOUT_MS = 30_000;

export function createRequestTimeoutError(timeoutMs = PLAYLIST_REQUEST_TIMEOUT_MS) {
    const error = new Error(`The request timed out after ${Math.round(timeoutMs / 1000)} seconds. Please try again.`);
    error.code = "request_timeout";
    error.timeoutMs = timeoutMs;
    return error;
}

export async function fetchWithTimeout(input, init = {}, timeoutMs = PLAYLIST_REQUEST_TIMEOUT_MS) {
    const controller = new AbortController();
    const callerSignal = init?.signal;
    let abortFromCaller = false;
    const onCallerAbort = () => {
        abortFromCaller = true;
        controller.abort(callerSignal.reason);
    };
    if (callerSignal) {
        if (callerSignal.aborted) {
            onCallerAbort();
        }
        else {
            callerSignal.addEventListener("abort", onCallerAbort, { once: true });
        }
    }
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
        const response = await fetch(input, { ...init, signal: controller.signal });
        await response.clone().arrayBuffer();
        return response;
    }
    catch (error) {
        if (!abortFromCaller && controller.signal.aborted) {
            throw createRequestTimeoutError(timeoutMs);
        }
        throw error;
    }
    finally {
        clearTimeout(timer);
        callerSignal?.removeEventListener("abort", onCallerAbort);
    }
}

export function getPlaylistLoadErrorMessage(response, apiError, fallbackMessage, context = "load") {
    if (apiError?.code === "spotify_playlist_app_restricted") {
        return apiError.message || "Spotify blocks this playlist for this app.";
    }
    if (apiError?.code === "spotify_login_required_or_restricted") {
        return apiError.message || "This playlist needs Spotify login, or Spotify blocks it because it is Spotify-owned/editorial.";
    }
    if (apiError?.code === "spotify_playlist_access_restricted") {
        return apiError.message || "Spotify would not expose this playlist to VinylMatch.";
    }
    if (response?.status === 401) {
        return context === "open"
            ? "This playlist is private. Log in with Spotify to open it."
            : "This playlist is private. Log in with Spotify to load it.";
    }
    if (apiError?.message) {
        return apiError.message;
    }
    return fallbackMessage;
}

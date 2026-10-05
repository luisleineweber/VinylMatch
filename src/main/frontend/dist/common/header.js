export function isTrustedSpotifyAuthCallbackMessage(messageEvent, expectedOrigin, popup) {
    return messageEvent?.origin === expectedOrigin
        && messageEvent?.source === popup
        && messageEvent?.data?.type === "spotify-auth-callback";
}

export function spotifyAuthPollFailure(popupClosed, elapsedMs, timeoutMs = 120000) {
    if (elapsedMs >= timeoutMs) {
        return { code: "spotify_login_timeout", message: "Spotify login timed out. Start the login again." };
    }
    if (popupClosed) {
        return { code: "spotify_popup_closed", message: "The Spotify login window was closed before login completed. Please try again." };
    }
    return null;
}

export async function injectHeader() {
    const container = document.getElementById("header");
    if (!container)
        return;
    const THEME_KEY = "vinylmatch_theme";
    const resolveEffectiveTheme = () => {
        const explicit = document.documentElement.dataset.theme;
        if (explicit === "light" || explicit === "dark")
            return explicit;
        return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    };
    const applyTheme = (theme) => {
        if (theme === "light" || theme === "dark") {
            document.documentElement.dataset.theme = theme;
        }
        else {
            delete document.documentElement.dataset.theme;
        }
    };
    const initThemeToggle = () => {
        const btn = container.querySelector("#theme-toggle");
        if (!(btn instanceof HTMLButtonElement))
            return;
        const render = () => {
            const current = resolveEffectiveTheme();
            btn.textContent = current === "dark" ? "Light" : "Dark";
            btn.setAttribute("aria-pressed", String(current === "dark"));
            btn.title = current === "dark" ? "Switch to light mode" : "Switch to dark mode";
        };
        btn.addEventListener("click", () => {
            const current = resolveEffectiveTheme();
            const next = current === "dark" ? "light" : "dark";
            try {
                localStorage.setItem(THEME_KEY, next);
            }
            catch (_a) {
                /* ignore */
            }
            applyTheme(next);
            render();
        });
        render();
    };
    const emitAuthState = (loggedIn, isAdmin) => {
        try {
            window.dispatchEvent(new CustomEvent("vm:auth-state", { detail: { loggedIn, isAdmin } }));
        }
        catch (_a) {
            /* ignore */
        }
    };
    const updateCurationLink = (isAdmin) => {
        const curationLink = container.querySelector('a[href="/curation.html"]');
        if (curationLink?.parentElement) {
            if (isAdmin) {
                curationLink.parentElement.classList.remove("hidden");
            }
            else {
                curationLink.parentElement.classList.add("hidden");
            }
        }
    };
    try {
        const res = await fetch("/common/header.html", { cache: "no-cache" });
        if (!res.ok)
            throw new Error("HTTP " + res.status);
        container.innerHTML = await res.text();
        initThemeToggle();
        const authStatus = container.querySelector("#spotify-auth-status");
        const setAuthStatus = (message, tone = "info") => {
            if (!(authStatus instanceof HTMLElement))
                return;
            authStatus.textContent = message || "";
            authStatus.className = `auth-status auth-status-${tone}`;
            authStatus.hidden = !message;
        };
        const emitAuthError = (code, message) => {
            setAuthStatus(message, "error");
            window.dispatchEvent(new CustomEvent("vm:auth-error", {
                detail: { code, message }
            }));
        };
        const readApiError = async (response) => {
            try {
                const payload = await response.json();
                return {
                    code: payload?.error?.code || "auth_login_failed",
                    message: payload?.error?.message || "Login could not be started. Please try again."
                };
            }
            catch (_a) {
                return { code: "auth_login_failed", message: "Login could not be started. Please try again." };
            }
        };
        // Aktiver Link markieren
        const rawPath = (location.pathname || "/").toLowerCase().replace(/\/+$/, "") || "/";
        const path = rawPath === "/" || rawPath.endsWith("/home.html")
            ? "/home.html"
            : (rawPath.endsWith("/playlist.html") ? "/playlist.html" : rawPath);
        container.querySelectorAll("a[href]").forEach((a) => {
            const hrefPath = a.pathname.toLowerCase();
            const normalizedHref = hrefPath === "/" ? "/home.html" : hrefPath;
            const normalizedPath = path === "/" ? "/home.html" : path;
            if (normalizedHref === normalizedPath) {
                a.classList.add("active");
            }
        });
        // Spotify-Login-Button erzeugen
        const createSpotifyButton = (loggedIn) => {
            const li = document.createElement("li");
            const btn = document.createElement("a");
            btn.id = "spotify-login-btn";
            btn.href = "#";
            btn.className = loggedIn ? "spotify-btn logged-in" : "spotify-btn";
            const iconSrc = loggedIn ? "/design/spotify_green.svg" : "/design/spotify_trans_black.svg";
            const iconClass = loggedIn ? "spotify-logo spotify-logo-green" : "spotify-logo spotify-logo-white";
            btn.innerHTML = `
                <img class="${iconClass}" src="${iconSrc}" alt="" aria-hidden="true">
                ${loggedIn ? "Log out" : "Log in with Spotify"}
            `;
            li.appendChild(btn);
            return li;
        };
        const updateSpotifyButton = (loggedIn, isAdmin = false) => {
            const navRight = container.querySelector(".navigation.navigation-right");
            if (!navRight)
                return;
            const oldBtnLi = navRight.querySelector("#spotify-login-btn")?.parentElement;
            if (oldBtnLi)
                oldBtnLi.remove();
            navRight.appendChild(createSpotifyButton(loggedIn));
            emitAuthState(!!loggedIn, !!isAdmin);
            updateCurationLink(isAdmin);
            const spotifyBtn = container.querySelector("#spotify-login-btn");
            if (spotifyBtn) {
                spotifyBtn.addEventListener("click", async (event) => {
                    event.preventDefault();
                    if (loggedIn) {
                        try {
                            const r = await fetch("/api/auth/logout", { method: "POST", credentials: "include" });
                            if (!r.ok && r.status !== 204)
                                throw new Error("HTTP " + r.status);
                            setAuthStatus("You are logged out of Spotify.", "info");
                        }
                        catch (_a) {
                            emitAuthError("spotify_logout_failed", "Spotify logout failed. Please try again.");
                            return;
                        }
                        updateSpotifyButton(false, false);
                    }
                    else {
                        let popup;
                        try {
                            setAuthStatus("Opening Spotify login…", "info");
                            popup = window.open("about:blank", "spotify-oauth", "popup,width=520,height=720");
                            if (!popup) {
                                emitAuthError("spotify_popup_blocked", "Your browser blocked the Spotify login window. Allow popups for this site and try again.");
                                return;
                            }
                            const r = await fetch("/api/auth/login", { method: "POST", credentials: "include" });
                            if (!r.ok) {
                                const apiError = await readApiError(r);
                                popup.close();
                                emitAuthError(apiError.code, apiError.message);
                                return;
                            }
                            const data = await r.json();
                            const url = data?.authorizeUrl;
                            if (typeof url !== "string" || !url) {
                                popup.close();
                                emitAuthError("spotify_authorize_url_missing", "Spotify did not provide a login link. Please try again.");
                                return;
                            }
                            popup.location.href = url;
                            setAuthStatus("Finish the login in the Spotify window.", "info");
                            const startedAt = Date.now();
                            let settled = false;
                            let pollTimer;
                            const cleanup = () => {
                                settled = true;
                                if (pollTimer)
                                    clearTimeout(pollTimer);
                                window.removeEventListener("message", onCallbackMessage);
                            };
                            const fail = (code, message) => {
                                if (settled)
                                    return;
                                cleanup();
                                emitAuthError(code, message);
                            };
                            const finishFromStatus = async () => {
                                let statusRes;
                                try {
                                    statusRes = await fetch("/api/auth/status", { cache: "no-cache", credentials: "include" });
                                }
                                catch (_a) {
                                    fail("spotify_status_failed", "VinylMatch could not confirm the Spotify login. Check your connection and try again.");
                                    return true;
                                }
                                if (!statusRes.ok) {
                                    fail("spotify_status_failed", "VinylMatch could not confirm the Spotify login. Please try again.");
                                    return true;
                                }
                                const statusData = await statusRes.json().catch(() => null);
                                if (typeof statusData?.loggedIn !== "boolean") {
                                    fail("spotify_status_invalid", "VinylMatch received an invalid login status. Please try again.");
                                    return true;
                                }
                                if (statusData.loggedIn) {
                                    cleanup();
                                    setAuthStatus("Spotify connected successfully.", "success");
                                    updateSpotifyButton(true, statusData?.isAdmin === true);
                                    return true;
                                }
                                return false;
                            };
                            const onCallbackMessage = async (messageEvent) => {
                                if (!isTrustedSpotifyAuthCallbackMessage(messageEvent, window.location.origin, popup))
                                    return;
                                const payload = messageEvent.data;
                                if (payload.success) {
                                    await finishFromStatus();
                                }
                                else {
                                    fail(typeof payload.code === "string" ? payload.code : "spotify_callback_failed", typeof payload.message === "string" ? payload.message : "Spotify login failed. Please try again.");
                                }
                            };
                            window.addEventListener("message", onCallbackMessage);
                            const poll = async () => {
                                if (settled)
                                    return;
                                const pollFailure = spotifyAuthPollFailure(popup.closed, Date.now() - startedAt);
                                if (pollFailure) {
                                    fail(pollFailure.code, pollFailure.message);
                                    return;
                                }
                                if (await finishFromStatus())
                                    return;
                                pollTimer = setTimeout(poll, 1500);
                            };
                            pollTimer = setTimeout(poll, 1500);
                        }
                        catch (e) {
                            if (popup && !popup.closed)
                                popup.close();
                            console.warn("Login could not be started", e);
                            emitAuthError("auth_login_failed", "Login could not be started. Please try again.");
                        }
                    }
                });
            }
        };
        updateSpotifyButton(false, false);
        try {
            const statusRes = await fetch("/api/auth/status", { cache: "no-cache", credentials: "include" });
            if (statusRes.ok) {
                const status = await statusRes.json();
                if (typeof status?.loggedIn === "boolean") {
                    updateSpotifyButton(status.loggedIn, status?.isAdmin === true);
                }
            }
        }
        catch (e) {
            console.warn("Failed to fetch auth status:", e);
        }
    }
    catch (e) {
        console.error("Failed to load header:", e);
    }
}

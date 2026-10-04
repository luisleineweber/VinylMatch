import { initQuicksearch } from "./quicksearch.js";

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
        initQuicksearch(container.querySelector("#quicksearch-open"));
        initThemeToggle();
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
                        }
                        catch (_a) {
                        }
                        updateSpotifyButton(false, false);
                    }
                    else {
                        try {
                            const r = await fetch("/api/auth/login", { method: "POST", credentials: "include" });
                            if (!r.ok)
                                throw new Error("HTTP " + r.status);
                            const data = await r.json();
                            const url = data?.authorizeUrl;
                            if (url) {
                                window.open(url, "_blank");
                                const start = Date.now();
                                const poll = setInterval(async () => {
                                    const statusRes = await fetch("/api/auth/status", { cache: "no-cache", credentials: "include" });
                                    if (statusRes.ok) {
                                        const statusData = await statusRes.json();
                                        if (statusData?.loggedIn) {
                                            updateSpotifyButton(true, statusData?.isAdmin === true);
                                            clearInterval(poll);
                                        }
                                    }
                                    if (Date.now() - start > 120000)
                                        clearInterval(poll);
                                }, 1500);
                            }
                        }
                        catch (e) {
                            console.warn("Login could not be started", e);
                            window.dispatchEvent(new CustomEvent("vm:auth-error", {
                                detail: { message: "Login could not be started. Please try again." }
                            }));
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

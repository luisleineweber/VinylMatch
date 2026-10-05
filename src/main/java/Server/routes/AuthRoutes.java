package Server.routes;

import Server.auth.SpotifyOAuthService;
import Server.cache.PlaylistCache;
import Server.http.ApiFilters;
import Server.http.HttpUtils;
import Server.session.SpotifySession;
import Server.session.SpotifySessionStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handles Spotify authentication routes with session-based multi-user support.
 */
public class AuthRoutes {

    private static final Logger log = LoggerFactory.getLogger(AuthRoutes.class);

    private final PlaylistCache playlistCache;
    private final SpotifySessionStore sessionStore;
    private final SpotifyOAuthService oauthService;

    public AuthRoutes(PlaylistCache playlistCache, SpotifySessionStore sessionStore, SpotifyOAuthService oauthService) {
        this.playlistCache = playlistCache;
        this.sessionStore = sessionStore;
        this.oauthService = oauthService;
    }

    public void register(HttpServer server) {
        server.createContext("/api/auth/status", this::handleStatus).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/auth/login", this::handleLogin).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/auth/logout", this::handleLogout).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/auth/callback", this::handleCallback).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
                return;
            }

            // Check session-based login first
            SpotifySession session = sessionStore.getSession(exchange);
            boolean loggedIn = session != null && session.isLoggedIn();
            
            String userId = null;
            boolean isAdmin = false;
            
            if (loggedIn && session != null) {
                userId = session.getUserId();
                if (userId != null && !userId.isBlank()) {
                    isAdmin = com.hctamlyniv.Config.getAdminUserIds().contains(userId);
                }
            }

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("loggedIn", loggedIn);
            if (userId != null && !userId.isBlank()) {
                response.put("userId", userId);
                response.put("isAdmin", isAdmin);
            }

            HttpUtils.sendJson(exchange, 200, response);
        } catch (Exception e) {
            log.warn("Auth status failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "auth_status_failed", "Failed to read auth status");
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            if (!oauthService.isConfigured()) {
                HttpUtils.sendApiError(exchange, 503, "spotify_not_configured", "Spotify OAuth is not configured");
                return;
            }

            // Create a session for this login attempt
            SpotifySession session = sessionStore.getOrCreateSession(exchange);
            
            // Build authorization URL with CSRF state
            URI redirectOverride = oauthService.isRedirectUriExplicit() ? null : deriveLoopbackRedirectUri(exchange);
            String url = oauthService.buildAuthorizationUrl(session.getSessionId(), redirectOverride);
            
            HttpUtils.sendJson(exchange, 200, Map.of("authorizeUrl", url));
        } catch (Exception e) {
            log.warn("Auth login start failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "auth_login_failed", "Failed to start login");
        }
    }

    /**
     * For local development, Spotify requires an explicit loopback IP literal (e.g. 127.0.0.1) and does not allow
     * localhost as a redirect URI. We still derive the port from the current request so dynamic ports work.
     *
     * This intentionally only trusts loopback hosts to avoid Host-header based redirect manipulation.
     */
    private static URI deriveLoopbackRedirectUri(HttpExchange exchange) {
        if (exchange == null) return null;
        String hostHeader = exchange.getRequestHeaders().getFirst("Host");
        if (hostHeader == null || hostHeader.isBlank()) return null;

        String scheme = HttpUtils.isSecureRequest(exchange) ? "https" : "http";
        URI base;
        try {
            base = URI.create(scheme + "://" + hostHeader.trim());
        } catch (Exception e) {
            return null;
        }

        String host = base.getHost();
        if (host == null || host.isBlank()) return null;
        String lowerHost = host.toLowerCase();
        boolean isLoopback = lowerHost.equals("localhost") || lowerHost.equals("127.0.0.1") || lowerHost.equals("::1");
        if (!isLoopback) return null;

        int port = base.getPort();
        if (port <= 0) {
            try {
                port = exchange.getLocalAddress().getPort();
            } catch (Exception ignored) {
                return null;
            }
        }

        try {
            String canonicalHost = lowerHost.equals("::1") ? "::1" : "127.0.0.1";
            return new URI(scheme, null, canonicalHost, port, "/api/auth/callback", null, null);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendText(exchange, 405, "Only GET is supported");
                return;
            }

            Map<String, String> params = HttpUtils.parseQueryParams(exchange.getRequestURI().getRawQuery());
            String code = params.get("code");
            String state = params.get("state");
            String error = params.get("error");

            // Handle OAuth errors
            if (error != null && !error.isBlank()) {
                String errorCode = "access_denied".equalsIgnoreCase(error)
                    ? "spotify_authorization_denied"
                    : "spotify_authorization_failed";
                String errorMessage = "access_denied".equalsIgnoreCase(error)
                    ? "Spotify authorization was cancelled. You can try again when you are ready."
                    : "Spotify could not authorize this login. Please try again.";
                sendCallbackHtml(exchange, false, errorCode, errorMessage);
                return;
            }

            if (code == null || code.isBlank()) {
                sendCallbackHtml(exchange, false, "spotify_callback_missing_code",
                    "Spotify returned without an authorization code. Please start the login again.");
                return;
            }

            if (state == null || state.isBlank()) {
                sendCallbackHtml(exchange, false, "spotify_callback_missing_state",
                    "The Spotify login could not be verified. Please start the login again.");
                return;
            }

            // Get or create session
            SpotifySession session = sessionStore.getSession(exchange);
            String receivedState = state;
            if (session == null) {
                String host = exchange.getRequestHeaders().getFirst("Host");
                log.warn("No session cookie on Spotify callback (Host={}). This is usually a hostname mismatch (localhost vs 127.0.0.1) between /api/auth/login and the redirect URI. Ensure you use the same hostname (prefer 127.0.0.1) for both login and callback.", host);
                sendCallbackHtml(exchange, false, "spotify_session_missing",
                    "Your login session is missing or expired. Use the same address as before and start the login again.");
                return;
            } else {
                log.info("Found existing session for callback: {}", session.getSessionId());
            }

            log.info("Processing callback with code length: {}, state: {}", code != null ? code.length() : 0, receivedState);
            
            // Exchange code for tokens
            boolean success = oauthService.exchangeCodeForTokens(code, receivedState, session);
            
            if (success) {
                try {
                    // Store in session store
                    sessionStore.storeSession(session);
                } catch (Exception ignored) {}

                playlistCache.invalidateForAuthChange(session.getSessionId());
                sendCallbackHtml(exchange, true, "spotify_auth_success",
                    "Spotify is connected. You can return to VinylMatch.");
            } else {
                sendCallbackHtml(exchange, false, "spotify_token_exchange_failed",
                    "Spotify could not complete the login. Please start the login again.");
            }
        } catch (Exception e) {
            log.warn("Auth callback failed: {}", e.getMessage());
            sendCallbackHtml(exchange, false, "spotify_callback_failed",
                "The Spotify login failed unexpectedly. Please try again.");
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            // Destroy session
            sessionStore.destroySession(exchange);

            playlistCache.invalidateForAuthChange(null);
            HttpUtils.sendNoContent(exchange);
        } catch (Exception e) {
            log.warn("Logout failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "logout_failed", "Failed to logout");
        }
    }

    /**
     * Gets the access token for a request, checking session first then falling back to legacy.
     */
    public String getAccessToken(HttpExchange exchange) {
        // Try session-based token first
        SpotifySession session = sessionStore.getSession(exchange);
        if (session != null && session.isLoggedIn()) {
            if (session.isTokenExpired()) {
                refreshAndPersistSession(session);
            }
            String token = session.getAccessToken();
            if (token != null && !token.isBlank()) {
                return token;
            }
        }

        return null;
    }

    /**
     * Resolves the best available token for playlist loading.
     * Prefers a user session token and falls back to app-level client credentials.
     */
    public AccessTokenResolution resolvePlaylistAccessToken(HttpExchange exchange) {
        SpotifySession session = sessionStore.getSession(exchange);
        if (session != null && session.isLoggedIn()) {
            if (session.isTokenExpired()) {
                refreshAndPersistSession(session);
            }
            String token = session.getAccessToken();
            if (token != null && !token.isBlank()) {
                return new AccessTokenResolution(token, true);
            }
        }

        String appToken = oauthService.getClientCredentialsAccessToken().orElse(null);
        if (appToken != null && !appToken.isBlank()) {
            return new AccessTokenResolution(appToken, false);
        }
        return new AccessTokenResolution(null, false);
    }

    private boolean refreshAndPersistSession(SpotifySession session) {
        if (session == null) {
            return false;
        }
        boolean refreshed = oauthService.refreshAccessToken(session);
        if (refreshed) {
            try {
                sessionStore.storeSession(session);
            } catch (Exception e) {
                log.warn("Failed to persist refreshed Spotify session: {}", e.getMessage());
            }
        }
        return refreshed;
    }

    /**
     * Gets a user signature for cache keying.
     */
    public String getUserSignature(HttpExchange exchange) {
        SpotifySession session = sessionStore.getSession(exchange);
        if (session != null) {
            return session.getSessionId();
        }
        return "";
    }

    /**
     * Checks if the request is authenticated.
     */
    public boolean isAuthenticated(HttpExchange exchange) {
        SpotifySession session = sessionStore.getSession(exchange);
        if (session != null && session.isLoggedIn()) {
            return true;
        }
        return false;
    }

    private void sendCallbackHtml(HttpExchange exchange, boolean success, String code, String message) throws IOException {
        String status = success ? "Spotify connected" : "Spotify connection failed";
        String badge = success ? "SUCCESS" : "ERROR";
        String closeHint = success ? "This window closes automatically in a moment." : "You can close this window and try again.";
        String safeCode = escapeHtml(code);
        String safeMessage = escapeHtml(message);
        String html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <title>VinylMatch - %s</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="theme-color" content="#f5f5f0">
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Archivo+Black&family=Space+Mono:wght@400;700&display=swap" rel="stylesheet">
                <link rel="stylesheet" href="/styles/spotify-callback.css">
            </head>
            <body class="spotify-callback-page">
                <main
                    class="spotify-callback"
                    id="spotify-auth-callback"
                    data-auth-success="%s"
                    data-auth-code="%s"
                    data-auth-message="%s"
                    aria-labelledby="spotify-callback-title"
                    aria-describedby="spotify-callback-message spotify-callback-close-hint">
                    <div class="spotify-callback__accent" aria-hidden="true"></div>
                    <div class="spotify-callback__brand">
                        <img src="/design/spotify_green.svg" alt="" aria-hidden="true">
                        <span>VINYLMATCH / SPOTIFY</span>
                    </div>
                    <div class="spotify-callback__meta">
                        <span class="spotify-callback__kicker">Authentication result</span>
                        <span class="spotify-callback__badge %s" role="status" aria-live="polite">%s</span>
                    </div>
                    <h1 id="spotify-callback-title">%s</h1>
                    <p id="spotify-callback-message" class="spotify-callback__message">%s</p>
                    <p id="spotify-callback-close-hint" class="spotify-callback__close-hint">%s</p>
                    <div class="spotify-callback__actions">
                        <a class="spotify-callback__button" id="oauth-callback-action" href="/">
                            <span>Back to app</span>
                            <span aria-hidden="true">↗</span>
                        </a>
                    </div>
                    <p class="spotify-callback__footer">You can close this window after returning.</p>
                </main>
                <script type="module" src="/dist/spotify-callback.js"></script>
            </body>
            </html>
            """.formatted(
                status,
                success,
                safeCode,
                safeMessage,
                success ? "badge-success" : "badge-error",
                badge,
                status,
                safeMessage,
                closeHint
            );

        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    public record AccessTokenResolution(String token, boolean userAuthenticated) {}
}

package Server.routes;

import Server.auth.DiscogsOAuthService;
import Server.DiscogsServiceRegistry;
import Server.http.HttpUtils;
import Server.http.ApiFilters;
import Server.http.filters.AdminOnlyFilter;
import Server.session.DiscogsSession;
import Server.session.DiscogsSessionStore;
import Server.session.SpotifySessionStore;
import Server.session.SpotifySession;
import com.hctamlyniv.DiscogsService;
import com.hctamlyniv.curation.CuratedLinkStore;
import com.hctamlyniv.curation.CurationAuditContext;
import com.hctamlyniv.curation.CurationVersionConflictException;
import com.hctamlyniv.curation.CurationVersionNotFoundException;
import com.hctamlyniv.curation.RedisCuratedLinkStore;
import com.hctamlyniv.discogs.model.CurationCandidate;
import com.hctamlyniv.discogs.model.CuratedLink;
import com.hctamlyniv.discogs.model.DiscogsProfile;
import com.hctamlyniv.discogs.model.DiscogsMatch;
import com.hctamlyniv.discogs.model.LibraryFlags;
import com.hctamlyniv.discogs.model.WishlistResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Handles Discogs-related API routes.
 */
public class DiscogsRoutes {

    private static final Logger log = LoggerFactory.getLogger(DiscogsRoutes.class);

    private final Supplier<DiscogsService> defaultDiscogsSupplier;
    private final DiscogsSessionStore sessionStore;
    private final SpotifySessionStore spotifySessionStore;
    private final DiscogsOAuthService oauthService;
    private final CuratedLinkStore curatedLinkStore;
    private final DiscogsServiceRegistry serviceCache = new DiscogsServiceRegistry();

    public DiscogsRoutes(Supplier<DiscogsService> defaultDiscogsSupplier, DiscogsSessionStore sessionStore, SpotifySessionStore spotifySessionStore) {
        this.defaultDiscogsSupplier = defaultDiscogsSupplier;
        this.sessionStore = sessionStore;
        this.spotifySessionStore = spotifySessionStore;
        this.oauthService = new DiscogsOAuthService();
        this.curatedLinkStore = new RedisCuratedLinkStore(new com.fasterxml.jackson.databind.ObjectMapper());
    }

    public void register(HttpServer server) {
        server.createContext("/api/discogs/batch", this::handleBatch).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/discogs/search", this::handleSearch).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/discogs/status", this::handleStatus).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/discogs/login", this::handleLogin).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/discogs/oauth/status", this::handleOAuthStatus).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/discogs/oauth/start", this::handleOAuthStart).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/discogs/oauth/callback", this::handleOAuthCallback).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/discogs/logout", this::handleLogout).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/discogs/wishlist/add", this::handleWishlistAdd).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/discogs/wishlist", this::handleWishlist).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/discogs/library-status", this::handleLibraryStatus).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        AdminOnlyFilter adminFilter = new AdminOnlyFilter(spotifySessionStore);
        server.createContext("/api/discogs/curation/candidates", this::handleCurationCandidates).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting(), adminFilter)
        );
        server.createContext("/api/discogs/curation/save", this::handleCurationSave).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting(), adminFilter)
        );
        server.createContext("/api/discogs/curation/history", this::handleCurationHistory).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting(), adminFilter)
        );
        server.createContext("/api/discogs/curation/rollback", this::handleCurationRollback).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting(), adminFilter)
        );
    }

    // =========================================================================
    // Batch Search
    // =========================================================================

    private void handleBatch(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            Object tracksObj = payload.get("tracks");
            if (!(tracksObj instanceof List<?> tracksList) || tracksList.isEmpty()) {
                HttpUtils.sendApiError(exchange, 400, "invalid_payload", "Payload must contain a non-empty 'tracks' array");
                return;
            }

            DiscogsService discogs = resolveDiscogsService(exchange);

            List<Map<String, Object>> results = new ArrayList<>();
            Map<String, Optional<DiscogsMatch>> requestLookupCache = new HashMap<>();
            for (Object entry : tracksList) {
                if (!(entry instanceof Map<?, ?> track)) {
                    continue;
                }
                String key = HttpUtils.stringValue(track.get("key"));
                Integer index = HttpUtils.intValue(track.get("index"));
                String artist = HttpUtils.stringValue(track.get("artist"));
                String album = HttpUtils.stringValue(track.get("album"));
                Integer year = HttpUtils.intValue(track.get("releaseYear"));
                String trackTitle = HttpUtils.stringValue(track.get("track"));
                String barcode = HttpUtils.stringValue(track.get("barcode"));

                Map<String, Object> resultEntry = new HashMap<>();
                if (key != null) resultEntry.put("key", key);
                if (index != null) resultEntry.put("index", index);

                if (artist == null || album == null) {
                    resultEntry.put("url", null);
                    resultEntry.put("matchType", "MANUAL_REVIEW");
                    resultEntry.put("confidence", "NONE");
                    resultEntry.put("source", "REQUEST");
                    resultEntry.put("reason", "Artist and album metadata are required for matching.");
                    resultEntry.put("vinylFormatConfirmed", false);
                    resultEntry.put("cacheHit", false);
                    results.add(resultEntry);
                    continue;
                }

                String lookupKey = buildBatchLookupKey(artist, album, year, barcode);
                Optional<DiscogsMatch> matchOpt;
                boolean cacheHit;
                if (lookupKey != null && requestLookupCache.containsKey(lookupKey)) {
                    matchOpt = requestLookupCache.get(lookupKey);
                    cacheHit = true;
                } else {
                    Optional<DiscogsMatch> cached = discogs.peekCachedMatch(artist, album, year, barcode);
                    matchOpt = discogs.findAlbumMatch(artist, album, year, trackTitle, barcode);
                    cacheHit = cached.isPresent()
                            && matchOpt.isPresent()
                            && cached.get().equals(matchOpt.get());
                    if (lookupKey != null) {
                        requestLookupCache.put(lookupKey, matchOpt);
                    }
                }

                resultEntry.put("cacheHit", cacheHit);
                if (matchOpt.isPresent()) {
                    resultEntry.putAll(matchOpt.get().asMap());
                } else {
                    resultEntry.put("url", null);
                    resultEntry.put("matchType", "MANUAL_REVIEW");
                    resultEntry.put("confidence", "NONE");
                    resultEntry.put("source", "DISCOGS_CATALOG");
                    resultEntry.put("reason", "No Discogs result was found.");
                    resultEntry.put("vinylFormatConfirmed", false);
                }
                results.add(resultEntry);
            }

            HttpUtils.sendJson(exchange, 200, Map.of("results", results));
        } catch (HttpUtils.RequestTooLargeException e) {
            HttpUtils.sendApiError(exchange, 413, "payload_too_large", "Request body too large");
        } catch (Exception e) {
            log.warn("Discogs batch failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_batch_failed", "Discogs batch search failed");
        }
    }

    // =========================================================================
    // Single Search
    // =========================================================================

    private void handleSearch(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String artist = HttpUtils.stringValue(payload.get("artist"));
            String album = HttpUtils.stringValue(payload.get("album"));
            Integer year = parseYear(payload.get("releaseYear"));
            String trackTitle = HttpUtils.stringValue(payload.get("track"));

            if (artist == null || album == null) {
                HttpUtils.sendApiError(exchange, 400, "invalid_payload", "Fields 'artist' and 'album' are required");
                return;
            }

            DiscogsService discogs = resolveDiscogsService(exchange);

            Optional<DiscogsMatch> matchOpt = discogs.findAlbumMatch(artist, album, year, trackTitle, null);
            if (matchOpt.isEmpty()) {
                HttpUtils.sendApiError(exchange, 404, "not_found", "No Discogs match found");
                return;
            }

            HttpUtils.sendJson(exchange, 200, matchOpt.get().asMap());
        } catch (HttpUtils.RequestTooLargeException e) {
            HttpUtils.sendApiError(exchange, 413, "payload_too_large", "Request body too large");
        } catch (Exception e) {
            log.warn("Discogs search failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_search_failed", "Discogs search failed");
        }
    }

    // =========================================================================
    // Session Management
    // =========================================================================

    private void handleStatus(HttpExchange exchange) throws IOException {
        try {
            HttpUtils.addCorsHeaders(exchange);
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
                return;
            }

            DiscogsSession session = sessionStore.getSession(exchange);
            Map<String, Object> payload = new HashMap<>();
            payload.put("loggedIn", session != null && session.username() != null);
            payload.put("oauthConfigured", oauthService.isConfigured());
            payload.put("oauthSession", session != null && session.oauthSession());
            if (session != null) {
                payload.put("username", session.username());
                payload.put("name", session.displayName());
            }
            HttpUtils.sendJson(exchange, 200, payload);
        } catch (Exception e) {
            log.warn("Discogs status failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_status_failed", "Failed to read Discogs status");
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String token = HttpUtils.stringValue(payload.get("token"));
            
            // Security: User-Agent is fixed to configured value, not client-controlled
            String userAgent = com.hctamlyniv.Config.getDiscogsUserAgent();

            if (token == null || token.isBlank()) {
                HttpUtils.sendApiError(exchange, 400, "missing_discogs_token", "Discogs user token is required");
                return;
            }

            String ua = (userAgent == null || userAgent.isBlank()) ? "VinylMatch/1.0" : userAgent;
            DiscogsService service = getOrCreateService(token, null, ua);
            DiscogsProfile profile = service.fetchProfile().orElse(null);
            if (profile == null || profile.username() == null) {
                HttpUtils.sendApiError(exchange, 401, "discogs_login_failed", "Discogs token invalid or access denied");
                return;
            }

            DiscogsSession session = sessionStore.createSession(exchange, token, ua, profile.username(), profile.name());
            Map<String, Object> response = new HashMap<>();
            response.put("username", session.username());
            response.put("name", session.displayName());
            HttpUtils.sendJson(exchange, 200, response);
        } catch (HttpUtils.RequestTooLargeException e) {
            HttpUtils.sendApiError(exchange, 413, "payload_too_large", "Request body too large");
        } catch (Exception e) {
            log.warn("Discogs login failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_login_failed", "Discogs login failed");
        }
    }

    private void handleOAuthStatus(HttpExchange exchange) throws IOException {
        try {
            HttpUtils.addCorsHeaders(exchange);
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
                return;
            }
            HttpUtils.sendJson(exchange, 200, Map.of("configured", oauthService.isConfigured()));
        } catch (Exception e) {
            log.warn("Discogs OAuth status failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_oauth_status_failed", "Failed to read Discogs OAuth status");
        }
    }

    private void handleOAuthStart(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }
            if (!oauthService.isConfigured()) {
                HttpUtils.sendApiError(exchange, 503, "discogs_oauth_not_configured", "Discogs OAuth is not configured");
                return;
            }

            URI redirectOverride = oauthService.isRedirectUriExplicit() ? null : deriveLoopbackDiscogsCallback(exchange);
            Optional<String> authorizeUrl = oauthService.buildAuthorizationUrl(redirectOverride);
            if (authorizeUrl.isEmpty()) {
                HttpUtils.sendApiError(exchange, 502, "discogs_oauth_start_failed", "Discogs OAuth temporarily unavailable. Retry in a few seconds.");
                return;
            }
            HttpUtils.sendJson(exchange, 200, Map.of("authorizeUrl", authorizeUrl.get()));
        } catch (Exception e) {
            log.warn("Discogs OAuth start failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_oauth_start_failed", "Failed to start Discogs OAuth");
        }
    }

    private void handleOAuthCallback(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendText(exchange, 405, "Only GET is supported");
                return;
            }

            Map<String, String> params = HttpUtils.parseQueryParams(exchange.getRequestURI().getRawQuery());
            String error = params.get("error");
            if (error != null && !error.isBlank()) {
                sendOAuthCallbackHtml(exchange, false, "Discogs authorization denied: " + error);
                return;
            }

            String requestToken = params.get("oauth_token");
            String verifier = params.get("oauth_verifier");
            String state = params.get("state");
            if (requestToken == null || requestToken.isBlank() || verifier == null || verifier.isBlank() || state == null || state.isBlank()) {
                sendOAuthCallbackHtml(exchange, false, "Missing OAuth callback parameters.");
                return;
            }

            Optional<DiscogsOAuthService.AccessTokenResponse> access = oauthService.exchangeAccessToken(requestToken, verifier, state);
            if (access.isEmpty()) {
                sendOAuthCallbackHtml(exchange, false, "Discogs OAuth token exchange failed.");
                return;
            }

            String userAgent = com.hctamlyniv.Config.getDiscogsUserAgent();
            String ua = (userAgent == null || userAgent.isBlank()) ? "VinylMatch/1.0" : userAgent;
            DiscogsOAuthService.AccessTokenResponse tokenResponse = access.get();
            DiscogsService service = getOrCreateService(tokenResponse.token(), tokenResponse.tokenSecret(), ua);
            if (service == null) {
                sendOAuthCallbackHtml(exchange, false, "Discogs OAuth service initialization failed.");
                return;
            }

            DiscogsProfile profile = service.fetchProfile().orElse(null);
            if (profile == null || profile.username() == null) {
                sendOAuthCallbackHtml(exchange, false, "Discogs profile lookup failed after OAuth.");
                return;
            }

            sessionStore.createSession(
                    exchange,
                    tokenResponse.token(),
                    tokenResponse.tokenSecret(),
                    ua,
                    profile.username(),
                    profile.name()
            );
            sendOAuthCallbackHtml(exchange, true, "Discogs connected successfully.");
        } catch (Exception e) {
            log.warn("Discogs OAuth callback failed: {}", e.getMessage());
            sendOAuthCallbackHtml(exchange, false, "Discogs OAuth callback failed.");
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            DiscogsSession current = sessionStore.getSession(exchange);
            if (current != null) {
                serviceCache.invalidate(current.token(), current.tokenSecret(), current.userAgent());
            }
            sessionStore.destroySession(exchange);
            HttpUtils.sendNoContent(exchange);
        } catch (Exception e) {
            log.warn("Discogs logout failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_logout_failed", "Discogs logout failed");
        }
    }

    // =========================================================================
    // Wishlist Operations
    // =========================================================================

    private void handleWishlist(HttpExchange exchange) throws IOException {
        try {
            HttpUtils.addCorsHeaders(exchange);
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
                return;
            }

            DiscogsSession session = sessionStore.getSession(exchange);
            if (session == null || session.username() == null) {
                HttpUtils.sendApiError(exchange, 401, "discogs_login_required", "Discogs login required");
                return;
            }

            int limit = 12;
            int page = 1;
            Map<String, String> params = HttpUtils.parseQueryParams(exchange.getRequestURI().getRawQuery());
            if (params.containsKey("limit")) {
                try {
                    int parsed = Integer.parseInt(params.get("limit"));
                    if (parsed > 0 && parsed <= 50) limit = parsed;
                } catch (NumberFormatException ignored) {}
            }
            if (params.containsKey("page")) {
                try {
                    int parsed = Integer.parseInt(params.get("page"));
                    if (parsed > 0 && parsed <= 500) page = parsed;
                } catch (NumberFormatException ignored) {}
            }

            DiscogsService service = getOrCreateService(session.token(), session.tokenSecret(), session.userAgent());
            WishlistResult wishlist = service.fetchWishlist(session.username(), page, limit);
            int total = Math.max(0, wishlist.total());
            boolean hasMore = page * limit < total;
            HttpUtils.sendJson(exchange, 200, Map.of(
                    "items", wishlist.items(),
                    "total", total,
                    "page", page,
                    "limit", limit,
                    "hasMore", hasMore
            ));
        } catch (Exception e) {
            log.warn("Discogs wishlist failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_wishlist_failed", "Failed to load Discogs wishlist");
        }
    }

    private void handleWishlistAdd(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            DiscogsSession session = sessionStore.getSession(exchange);
            if (session == null || session.username() == null) {
                HttpUtils.sendApiError(exchange, 401, "discogs_login_required", "Discogs login required");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String url = HttpUtils.stringValue(payload.get("url"));
            if (!HttpUtils.isDiscogsWebUrl(url)) {
                HttpUtils.sendApiError(exchange, 400, "invalid_url", "Parameter 'url' is required");
                return;
            }

            DiscogsService service = getOrCreateService(session.token(), session.tokenSecret(), session.userAgent());
            Integer releaseId = service.resolveReleaseIdFromUrl(url).orElse(null);
            if (releaseId == null) {
                HttpUtils.sendApiError(exchange, 422, "invalid_url", "Could not resolve release ID from URL");
                return;
            }

            boolean added = service.addToWantlist(session.username(), releaseId);
            if (!added) {
                HttpUtils.sendApiError(exchange, 502, "discogs_wantlist_add_failed", "Discogs rejected the add request");
                return;
            }
            HttpUtils.sendJson(exchange, 200, Map.of(
                    "added", true,
                    "releaseId", releaseId
            ));
        } catch (HttpUtils.RequestTooLargeException e) {
            HttpUtils.sendApiError(exchange, 413, "payload_too_large", "Request body too large");
        } catch (Exception e) {
            log.warn("Discogs wantlist add failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_wantlist_add_failed", "Failed to add to Discogs wantlist");
        }
    }

    // =========================================================================
    // Library Status
    // =========================================================================

    private void handleLibraryStatus(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            DiscogsSession session = sessionStore.getSession(exchange);
            if (session == null || session.username() == null) {
                HttpUtils.sendApiError(exchange, 401, "discogs_login_required", "Discogs login required");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            Object urlsObj = payload.get("urls");
            if (!(urlsObj instanceof List<?> list) || list.isEmpty()) {
                HttpUtils.sendApiError(exchange, 400, "invalid_payload", "Payload must contain a non-empty 'urls' array");
                return;
            }

            List<String> urls = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s && HttpUtils.isDiscogsWebUrl(s)) {
                    urls.add(s.trim());
                }
            }
            if (urls.isEmpty()) {
                HttpUtils.sendApiError(exchange, 400, "invalid_payload", "No valid Discogs URLs provided");
                return;
            }

            DiscogsService service = getOrCreateService(session.token(), session.tokenSecret(), session.userAgent());
            Map<String, Integer> resolved = new HashMap<>();
            for (String url : urls) {
                service.resolveReleaseIdFromUrl(url).ifPresent(id -> resolved.put(url, id));
            }

            Map<Integer, LibraryFlags> flags = service.lookupLibraryFlags(session.username(), new HashSet<>(resolved.values()));
            List<Map<String, Object>> results = new ArrayList<>();
            for (String url : urls) {
                Integer id = resolved.get(url);
                LibraryFlags flag = (id == null) ? null : flags.get(id);
                Map<String, Object> entry = new HashMap<>();
                entry.put("url", url);
                entry.put("releaseId", id);
                entry.put("inWishlist", flag != null && flag.inWishlist());
                entry.put("inCollection", flag != null && flag.inCollection());
                results.add(entry);
            }

            HttpUtils.sendJson(exchange, 200, Map.of("results", results));
        } catch (HttpUtils.RequestTooLargeException e) {
            HttpUtils.sendApiError(exchange, 413, "payload_too_large", "Request body too large");
        } catch (Exception e) {
            log.warn("Discogs library status failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_library_failed", "Failed to load Discogs library status");
        }
    }

    // =========================================================================
    // Curation
    // =========================================================================

    private void handleCurationCandidates(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String artist = HttpUtils.stringValue(payload.get("artist"));
            String album = HttpUtils.stringValue(payload.get("album"));
            Integer year = HttpUtils.intValue(payload.get("year"));
            String trackTitle = HttpUtils.stringValue(payload.get("trackTitle"));

            if (artist == null && album == null && trackTitle == null) {
                HttpUtils.sendApiError(exchange, 400, "invalid_payload", "Invalid request payload");
                return;
            }

            DiscogsService discogs = defaultDiscogsSupplier.get();
            if (discogs == null) {
                HttpUtils.sendApiError(exchange, 503, "discogs_not_configured", "Discogs token not configured");
                return;
            }

            List<CurationCandidate> candidates = discogs.fetchCurationCandidates(
                    artist, album, year, trackTitle, 4);
            long curationVersion = curatedLinkStore.find(CuratedLinkStore.normalizeKey(artist, album, year))
                    .map(CuratedLink::version).orElse(0L);
            HttpUtils.sendJson(exchange, 200, Map.of("candidates", candidates, "curationVersion", curationVersion));
        } catch (HttpUtils.RequestTooLargeException e) {
            HttpUtils.sendApiError(exchange, 413, "payload_too_large", "Request body too large");
        } catch (Exception e) {
            log.warn("Discogs curation candidates failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_curation_failed", "Failed to load curation candidates");
        }
    }

    private void handleCurationSave(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }

            String body = HttpUtils.readRequestBody(exchange);
            Map<?, ?> payload = HttpUtils.getMapper().readValue(body, Map.class);
            String artist = HttpUtils.stringValue(payload.get("artist"));
            String album = HttpUtils.stringValue(payload.get("album"));
            Integer year = HttpUtils.intValue(payload.get("year"));
            String trackTitle = HttpUtils.stringValue(payload.get("trackTitle"));
            String barcode = HttpUtils.stringValue(payload.get("barcode"));
            String url = HttpUtils.stringValue(payload.get("url"));
            String thumb = HttpUtils.stringValue(payload.get("thumb"));
            Integer expectedVersion = HttpUtils.intValue(payload.get("expectedVersion"));
            String reason = HttpUtils.stringValue(payload.get("reason"));

            if (expectedVersion == null || expectedVersion < 0) {
                HttpUtils.sendApiError(exchange, 400, "invalid_expected_version", "expectedVersion must be zero or positive");
                return;
            }
            if (reason == null) {
                HttpUtils.sendApiError(exchange, 400, "missing_reason", "A change reason is required");
                return;
            }

            if (!HttpUtils.isDiscogsWebUrl(url)) {
                HttpUtils.sendApiError(exchange, 400, "invalid_url", "Parameter 'url' is required and must be a valid Discogs URL");
                return;
            }

            String safeUrl = com.hctamlyniv.discogs.DiscogsUrlUtils.sanitizeDiscogsWebUrl(url);
            String safeThumb = com.hctamlyniv.discogs.DiscogsUrlUtils.sanitizeDiscogsWebUrl(thumb);
            
            String normalizedKey = CuratedLinkStore.normalizeKey(artist, album, year);
            
            CuratedLink link = new CuratedLink(
                normalizedKey,
                artist,
                album,
                year,
                trackTitle,
                barcode,
                safeUrl,
                safeThumb,
                java.time.Instant.now().toString(),
                "manual"
            );
            
            CuratedLink saved = curatedLinkStore.save(link, expectedVersion, auditContext(exchange, reason));
            
            log.info("Saved curated link: {} -> {}", normalizedKey, safeUrl);
            HttpUtils.sendJson(exchange, 200, Map.of("saved", true, "cacheKey", normalizedKey, "entry", saved));
        } catch (HttpUtils.RequestTooLargeException e) {
            HttpUtils.sendApiError(exchange, 413, "payload_too_large", "Request body too large");
        } catch (CurationVersionConflictException e) {
            HttpUtils.sendApiError(exchange, 409, "curation_version_conflict", e.getMessage());
        } catch (Exception e) {
            log.warn("Discogs curation save failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "discogs_curation_save_failed", "Failed to save curated link");
        }
    }

    private void handleCurationHistory(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
                return;
            }
            String cacheKey = HttpUtils.parseQueryParams(exchange.getRequestURI().getRawQuery()).get("cacheKey");
            if (cacheKey == null || cacheKey.isBlank()) {
                HttpUtils.sendApiError(exchange, 400, "missing_cache_key", "cacheKey is required");
                return;
            }
            HttpUtils.sendJson(exchange, 200, Map.of("cacheKey", cacheKey, "history", curatedLinkStore.history(cacheKey)));
        } catch (Exception e) {
            log.warn("Curation history failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "curation_history_failed", "Failed to load curation history");
        }
    }

    private void handleCurationRollback(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only POST is supported");
                return;
            }
            Map<?, ?> payload = HttpUtils.getMapper().readValue(HttpUtils.readRequestBody(exchange), Map.class);
            String cacheKey = HttpUtils.stringValue(payload.get("cacheKey"));
            Integer targetVersion = HttpUtils.intValue(payload.get("targetVersion"));
            Integer expectedVersion = HttpUtils.intValue(payload.get("expectedVersion"));
            String reason = HttpUtils.stringValue(payload.get("reason"));
            if (cacheKey == null || targetVersion == null || targetVersion < 1
                    || expectedVersion == null || expectedVersion < 1 || reason == null) {
                HttpUtils.sendApiError(exchange, 400, "invalid_rollback", "cacheKey, targetVersion, expectedVersion and reason are required");
                return;
            }
            CuratedLink restored = curatedLinkStore.rollback(
                    cacheKey, targetVersion, expectedVersion, auditContext(exchange, reason));
            HttpUtils.sendJson(exchange, 200, Map.of("rolledBack", true, "entry", restored));
        } catch (HttpUtils.RequestTooLargeException e) {
            HttpUtils.sendApiError(exchange, 413, "payload_too_large", "Request body too large");
        } catch (CurationVersionConflictException e) {
            HttpUtils.sendApiError(exchange, 409, "curation_version_conflict", e.getMessage());
        } catch (CurationVersionNotFoundException e) {
            HttpUtils.sendApiError(exchange, 404, "curation_version_not_found", e.getMessage());
        } catch (Exception e) {
            log.warn("Curation rollback failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "curation_rollback_failed", "Failed to roll back curation");
        }
    }

    private CurationAuditContext auditContext(HttpExchange exchange, String reason) {
        SpotifySession spotifySession = spotifySessionStore.getSession(exchange);
        String actor = spotifySession == null ? null : spotifySession.getUserId();
        String correlationId = exchange.getResponseHeaders().getFirst("X-Correlation-Id");
        return new CurationAuditContext(actor, reason, correlationId);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private DiscogsService resolveDiscogsService(HttpExchange exchange) {
        DiscogsSession session = sessionStore.getSession(exchange);
        if (session != null && session.token() != null && !session.token().isBlank()) {
            DiscogsService fromSession = getOrCreateService(session.token(), session.tokenSecret(), session.userAgent());
            if (fromSession != null) {
                return fromSession;
            }
        }
        DiscogsService fallback = defaultDiscogsSupplier.get();
        return fallback != null ? fallback : new DiscogsService(null, "VinylMatch/1.0");
    }

    private DiscogsService getOrCreateService(String token, String tokenSecret, String userAgent) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String ua = (userAgent == null || userAgent.isBlank()) ? "VinylMatch/1.0" : userAgent;
        String secretKey = (tokenSecret == null || tokenSecret.isBlank()) ? "" : tokenSecret;
        if (secretKey.isBlank()) {
            return serviceCache.get(token, null, ua, () -> new DiscogsService(token, ua));
        }
        return serviceCache.get(token, secretKey, ua, () -> new DiscogsService(
                token,
                secretKey,
                ua,
                com.hctamlyniv.Config.getDiscogsConsumerKey(),
                com.hctamlyniv.Config.getDiscogsConsumerSecret()
        ));
    }

    private static URI deriveLoopbackDiscogsCallback(HttpExchange exchange) {
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
            return new URI(scheme, null, canonicalHost, port, "/api/discogs/oauth/callback", null, null);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private void sendOAuthCallbackHtml(HttpExchange exchange, boolean success, String message) throws IOException {
        String status = success ? "Discogs Login Successful" : "Discogs Login Failed";
        String action = success ? "Closing window..." : "You can close this window.";
        String safeMessage = escapeHtml(message);

        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s</title>
                    <link rel="stylesheet" href="/styles/discogs-callback.css">
                </head>
                <body>
                    <main class="card" data-callback-message="%s" data-callback-success="%s">
                        <h1>%s</h1>
                        <p>%s</p>
                        <p class="muted">%s</p>
                    </main>
                    <script src="/dist/discogs-callback.js" defer></script>
                </body>
                </html>
                """.formatted(
                status,
                safeMessage,
                success,
                status,
                safeMessage,
                action
        );

        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private Integer parseYear(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String ys) {
            String ts = ys.trim();
            if (ts.length() >= 4) {
                try {
                    return Integer.parseInt(ts.substring(0, 4));
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static String buildBatchLookupKey(String artist, String album, Integer year, String barcode) {
        if (artist == null || album == null) {
            return null;
        }
        String normalizedArtist = normalizeLookupPart(artist);
        String normalizedAlbum = normalizeLookupPart(album);
        if (normalizedArtist.isBlank() || normalizedAlbum.isBlank()) {
            return null;
        }
        String yearPart = (year != null) ? String.valueOf(year) : "";
        return normalizedArtist + "|" + normalizedAlbum + "|" + yearPart + "|" + normalizeLookupPart(barcode);
    }

    private static String normalizeLookupPart(String value) {
        return (value == null) ? "" : value.trim().toLowerCase();
    }
}

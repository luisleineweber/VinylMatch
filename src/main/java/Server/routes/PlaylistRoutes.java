package Server.routes;

import Server.PlaylistData;
import Server.PlaylistSummary;
import Server.SpotifyPlaylistAccessClassifier;
import Server.UserPlaylistsResponse;
import Server.cache.PlaylistCache;
import Server.cache.PlaylistCacheKey;
import Server.http.ApiFilters;
import Server.http.HttpUtils;
import Server.http.ProviderExecutor;
import com.hctamlyniv.DiscogsService;
import com.hctamlyniv.ReceivingData;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.specification.Paging;
import se.michaelthelin.spotify.model_objects.specification.PlaylistSimplified;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Handles playlist-related API routes.
 */
public class PlaylistRoutes {

    private static final Logger log = LoggerFactory.getLogger(PlaylistRoutes.class);

    private final PlaylistCache playlistCache;
    private final Supplier<DiscogsService> discogsServiceSupplier;
    private final AuthRoutes authRoutes;

    public PlaylistRoutes(PlaylistCache playlistCache, Supplier<DiscogsService> discogsServiceSupplier, AuthRoutes authRoutes) {
        this.playlistCache = playlistCache;
        this.discogsServiceSupplier = discogsServiceSupplier;
        this.authRoutes = authRoutes;
    }

    public void register(HttpServer server) {
        server.createContext("/api/playlist", this::handleGetPlaylist).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
        server.createContext("/api/user/playlists", this::handleGetUserPlaylists).getFilters().addAll(
            java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting())
        );
    }

    private void handleGetPlaylist(HttpExchange exchange) throws IOException {
        PlaylistCacheKey cacheKey = null;
        try {
            HttpUtils.addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
                return;
            }

            Map<String, String> params = HttpUtils.parseQueryParams(exchange.getRequestURI().getRawQuery());
            String id = params.get("id");
            if (id == null || id.isBlank()) {
                HttpUtils.sendApiError(exchange, 400, "missing_playlist_id", "Missing or invalid 'id' query parameter");
                return;
            }

            int offset = 0;
            String offsetParam = params.get("offset");
            if (offsetParam != null && !offsetParam.isBlank()) {
                try {
                    offset = Math.max(0, Integer.parseInt(offsetParam));
                } catch (NumberFormatException e) {
                    HttpUtils.sendApiError(exchange, 400, "invalid_offset", "Invalid 'offset' query parameter");
                    return;
                }
            }

            int limit = -1;
            String limitParam = params.get("limit");
            if (limitParam != null && !limitParam.isBlank()) {
                try {
                    int parsedLimit = Integer.parseInt(limitParam);
                    if (parsedLimit > 0) {
                        limit = Math.min(parsedLimit, 500);
                    } else {
                        limit = -1;
                    }
                } catch (NumberFormatException e) {
                    HttpUtils.sendApiError(exchange, 400, "invalid_limit", "Invalid 'limit' query parameter");
                    return;
                }
            }
            // Safety default: don't accidentally fetch huge playlists in one request if limit is omitted.
            // The frontend uses paging and passes an explicit limit.
            if (limit <= 0) {
                limit = 50;
            }

            String limitLog = String.valueOf(limit);
            log.info("GET /api/playlist id={} offset={} limit={}", id, offset, limitLog);

            // Resolve best available token (user token first, app token fallback for public playlists)
            AuthRoutes.AccessTokenResolution tokenResolution = authRoutes.resolvePlaylistAccessToken(exchange);
            String token = tokenResolution.token();
            if (token == null || token.isBlank()) {
                HttpUtils.sendApiError(exchange, 401, "spotify_login_required", "Spotify login required");
                return;
            }
            boolean userAuthenticated = tokenResolution.userAuthenticated();

            String userSignature = authRoutes.getUserSignature(exchange);
            cacheKey = new PlaylistCacheKey(id, userSignature, offset, limit);
            DiscogsService discogsService = discogsServiceSupplier.get();

            PlaylistData playlistData = playlistCache.lookup(cacheKey);
            if (playlistData == null) {
                ReceivingData rd = new ReceivingData(token, id, discogsService);
                int requestedOffset = offset;
                int requestedLimit = limit;
                ReceivingData.PlaylistLoadResult loadResult = ProviderExecutor.call(
                    () -> rd.loadPlaylistDataResult(requestedOffset, requestedLimit)
                );
                playlistData = loadResult.playlistData();
                if (playlistData == null) {
                    playlistCache.remove(cacheKey);
                    var classifiedError = SpotifyPlaylistAccessClassifier.classify(loadResult.error(), userAuthenticated);
                    if (classifiedError.isPresent()) {
                        var apiError = classifiedError.get();
                        HttpUtils.sendApiError(exchange, apiError.status(), apiError.code(), apiError.message());
                        return;
                    }
                    if (!userAuthenticated) {
                        HttpUtils.sendApiError(exchange, 401, "spotify_login_required",
                                "Playlist unavailable without Spotify login (private or collaborative)");
                        return;
                    }
                    HttpUtils.sendApiError(exchange, 500, "playlist_load_failed", "Failed to load playlist data");
                    return;
                }
                playlistCache.store(cacheKey, playlistData);
            }

            HttpUtils.sendJson(exchange, 200, playlistData);
        } catch (ProviderExecutor.ProviderUnavailableException e) {
            if (cacheKey != null) playlistCache.remove(cacheKey);
            HttpUtils.sendApiError(exchange, 503, "provider_busy", "Spotify is busy or timed out; retry shortly");
        } catch (Exception e) {
            if (cacheKey != null) {
                playlistCache.remove(cacheKey);
            }
            log.warn("Playlist load failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "playlist_load_failed", "Failed to load playlist");
        }
    }

    private void handleGetUserPlaylists(HttpExchange exchange) throws IOException {
        try {
            HttpUtils.addCorsHeaders(exchange);
            String method = exchange.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(method)) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
                return;
            }

            String token = authRoutes.getAccessToken(exchange);
            if (token == null || token.isBlank()) {
                HttpUtils.sendApiError(exchange, 401, "spotify_login_required", "Spotify login required");
                return;
            }

            Map<String, String> params = HttpUtils.parseQueryParams(exchange.getRequestURI().getRawQuery());
            int offset = 0;
            String offsetParam = params.get("offset");
            if (offsetParam != null && !offsetParam.isBlank()) {
                try {
                    offset = Math.max(0, Integer.parseInt(offsetParam));
                } catch (NumberFormatException e) {
                    HttpUtils.sendApiError(exchange, 400, "invalid_offset", "Invalid 'offset' query parameter");
                    return;
                }
            }
            int limit = 50;
            String limitParam = params.get("limit");
            if (limitParam != null && !limitParam.isBlank()) {
                try {
                    int parsedLimit = Integer.parseInt(limitParam);
                    if (parsedLimit > 0) {
                        limit = Math.min(parsedLimit, 50);
                    }
                } catch (NumberFormatException e) {
                    HttpUtils.sendApiError(exchange, 400, "invalid_limit", "Invalid 'limit' query parameter");
                    return;
                }
            }

            SpotifyApi spotifyApi = new SpotifyApi.Builder().setAccessToken(token).build();
            int requestedOffset = offset;
            int requestedLimit = limit;
            Paging<PlaylistSimplified> page = ProviderExecutor.call(() -> spotifyApi.getListOfCurrentUsersPlaylists()
                .offset(requestedOffset)
                .limit(requestedLimit)
                .build()
                .execute());

            PlaylistSimplified[] items = page.getItems();
            List<PlaylistSummary> summaries = new ArrayList<>();
            if (items != null) {
                for (PlaylistSimplified item : items) {
                    if (item == null || item.getId() == null) {
                        continue;
                    }
                    String coverUrl = null;
                    if (item.getImages() != null && item.getImages().length > 0) {
                        coverUrl = item.getImages()[0].getUrl();
                    }
                    Integer trackCount = null;
                    if (item.getTracks() != null) {
                        trackCount = item.getTracks().getTotal();
                    }
                    String owner = (item.getOwner() != null) ? item.getOwner().getDisplayName() : null;
                    summaries.add(new PlaylistSummary(item.getId(), item.getName(), coverUrl, trackCount, owner));
                }
            }

            int total = page.getTotal();
            UserPlaylistsResponse payload = new UserPlaylistsResponse(summaries, total, offset, limit);
            HttpUtils.sendJson(exchange, 200, payload);
        } catch (ProviderExecutor.ProviderUnavailableException e) {
            HttpUtils.sendApiError(exchange, 503, "provider_busy", "Spotify is busy or timed out; retry shortly");
        } catch (SpotifyWebApiException e) {
            log.warn("Spotify API error: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 502, "spotify_api_error", "Spotify API error");
        } catch (Exception e) {
            log.warn("User playlists load failed: {}", e.getMessage());
            HttpUtils.sendApiError(exchange, 500, "user_playlists_failed", "Failed to load user playlists");
        }
    }
}

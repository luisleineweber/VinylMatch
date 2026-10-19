package Server.routes;

import Server.http.ApiFilters;
import Server.http.HttpUtils;
import com.hctamlyniv.DiscogsService;
import com.hctamlyniv.discogs.model.CatalogResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class QuickSearchRoutes {
    private static final Logger log = LoggerFactory.getLogger(QuickSearchRoutes.class);
    private static final Set<String> TYPES = Set.of("all", "artists", "albums", "songs");
    private final Function<HttpExchange, DiscogsService> serviceResolver;

    public QuickSearchRoutes(Function<HttpExchange, DiscogsService> serviceResolver) {
        this.serviceResolver = serviceResolver;
    }

    public void register(HttpServer server) {
        server.createContext("/api/quicksearch", this::handle).getFilters().addAll(
                java.util.List.of(ApiFilters.securityHeaders(), ApiFilters.rateLimiting()));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (HttpUtils.handleCorsPreflightIfNeeded(exchange)) return;
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!Set.of("/api/quicksearch", "/api/quicksearch/artist", "/api/quicksearch/album").contains(path)) {
                HttpUtils.sendApiError(exchange, 404, "not_found", "Search endpoint not found");
                return;
            }
            Map<String, String> params;
            try {
                params = HttpUtils.parseQueryParams(exchange.getRequestURI().getRawQuery());
            } catch (IllegalArgumentException e) {
                HttpUtils.sendApiError(exchange, 400, "invalid_query", "Invalid search parameters.");
                return;
            }
            CatalogResult<?> result;
            if ("/api/quicksearch".equals(path)) {
                String query = params.getOrDefault("q", "").trim();
                String type = params.getOrDefault("type", "all");
                if (query.length() < 2 || query.length() > 160 || !TYPES.contains(type)) {
                    HttpUtils.sendApiError(exchange, 400, "invalid_search", "Enter 2 to 160 characters and a valid search type.");
                    return;
                }
                result = serviceResolver.apply(exchange).catalog().search(query, type);
            } else {
                Integer id = positiveInteger(params.get("id"));
                Integer page = positiveInteger(params.getOrDefault("page", "1"));
                String kind = params.getOrDefault("kind", "master");
                boolean artist = path.endsWith("/artist");
                if (id == null || (artist && (page == null || page > 10000))
                        || (!artist && !Set.of("master", "release").contains(kind))) {
                    HttpUtils.sendApiError(exchange, 400, "invalid_catalog_item", "Enter a valid item ID, page and album kind.");
                    return;
                }
                var catalog = serviceResolver.apply(exchange).catalog();
                result = artist ? catalog.artist(id, page) : catalog.album(id, kind);
            }
            if (result.status() == 200) {
                HttpUtils.sendJson(exchange, 200, result.data());
            } else {
                if (result.status() == 429) exchange.getResponseHeaders().set("Retry-After", "60");
                HttpUtils.sendApiError(exchange, result.status(), result.code(), result.message());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            HttpUtils.sendApiError(exchange, 502, "discogs_unavailable", "The search was interrupted. Please try again.");
        } catch (Exception e) {
            log.warn("Quicksearch failed", e);
            HttpUtils.sendApiError(exchange, 502, "discogs_unavailable", "The search could not finish. Please try again.");
        }
    }

    private static Integer positiveInteger(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,8}")) return null;
        return Integer.valueOf(value);
    }
}

package Server.http.filters;

import Server.http.HttpUtils;
import Server.http.RateLimiter;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import Server.http.ForwardedRequestResolver;

public class RateLimitingFilter extends Filter {

    private final RateLimiter limiter;

    public RateLimitingFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getPath() : "";
        if (path != null && path.startsWith("/api/")) {
            String key = ForwardedRequestResolver.clientIp(exchange) + "|" + normalizePath(path);
            final RateLimiter.Result result;
            try {
                result = limiter.tryAcquire(key);
            } catch (RuntimeException e) {
                HttpUtils.sendApiError(exchange, 503, "rate_limiter_unavailable", "Request protection is temporarily unavailable");
                return;
            }
            exchange.getResponseHeaders().set("X-RateLimit-Limit", Integer.toString(limiter.limit()));
            exchange.getResponseHeaders().set("X-RateLimit-Remaining", Integer.toString(result.remainingTokens()));
            if (!result.allowed()) {
                exchange.getResponseHeaders().set("Retry-After", Integer.toString(result.retryAfterSeconds()));
                HttpUtils.addCorsHeaders(exchange);
                HttpUtils.sendApiError(exchange, 429, "rate_limited", "Too many requests");
                return;
            }
        }
        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Rate limits API requests";
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/api";
        return path.replaceAll("/[0-9a-fA-F-]{8,}(?=/|$)", "/:id")
            .replaceAll("/\\d+(?=/|$)", "/:id");
    }
}

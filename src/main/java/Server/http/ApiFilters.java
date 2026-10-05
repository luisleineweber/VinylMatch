package Server.http;

import Server.http.filters.CorrelationIdFilter;
import Server.http.filters.ErrorTrackingFilter;
import Server.http.filters.RateLimitingFilter;
import Server.http.filters.SecurityHeadersFilter;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;

public final class ApiFilters {

    private static final RateLimiter RATE_LIMITER = RateLimiter.fromEnv();
    private static final Filter RATE_LIMITING_FILTER = new RateLimitingFilter(RATE_LIMITER);
    private static final Filter SECURITY_HEADERS_FILTER = new SecurityHeadersFilter();
    private static final Filter CORRELATION_ID_FILTER = new CorrelationIdFilter();
    private static final Filter ERROR_TRACKING_FILTER = new ErrorTrackingFilter();
    private static final Filter OBSERVABLE_SECURITY_FILTER = new CompositeFilter(List.of(
        CORRELATION_ID_FILTER, ERROR_TRACKING_FILTER, SECURITY_HEADERS_FILTER
    ));

    private ApiFilters() {}

    public static Filter rateLimiting() {
        return RATE_LIMITING_FILTER;
    }
    
    public static Filter securityHeaders() {
        return OBSERVABLE_SECURITY_FILTER;
    }
    
    public static Filter correlationId() {
        return CORRELATION_ID_FILTER;
    }
    
    public static Filter errorTracking() {
        return ERROR_TRACKING_FILTER;
    }
    
    /**
     * Get all API filters in the correct order.
     * Order: CorrelationId -> ErrorTracking -> SecurityHeaders -> RateLimiting
     */
    public static java.util.List<Filter> getAllApiFilters() {
        return java.util.List.of(OBSERVABLE_SECURITY_FILTER, RATE_LIMITING_FILTER);
    }

    private static final class CompositeFilter extends Filter {
        private final List<Filter> filters;

        private CompositeFilter(List<Filter> filters) { this.filters = filters; }

        @Override
        public void doFilter(com.sun.net.httpserver.HttpExchange exchange, Chain chain) throws IOException {
            HttpHandler terminal = chain::doFilter;
            new Chain(filters, terminal).doFilter(exchange);
        }

        @Override public String description() { return "Correlation, error boundary, and security headers"; }
    }
}

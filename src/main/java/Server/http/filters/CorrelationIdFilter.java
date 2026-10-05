package Server.http.filters;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter that extracts or generates correlation IDs for request tracing.
 * 
 * The correlation ID is:
 1. Extracted from the X-Correlation-Id header if present
 2. Generated as a new UUID if not present
 3. Added to the response headers
 4. Stored in the MDC (Mapped Diagnostic Context) for logging
 5. Included in all log statements from this request
 */
public class CorrelationIdFilter extends Filter {
    
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String MDC_CORRELATION_ID_KEY = "correlationId";
    
    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        // Extract or generate correlation ID
        String correlationId = extractOrGenerateCorrelationId(exchange);
        
        // Store in MDC for logging
        MDC.put(MDC_CORRELATION_ID_KEY, correlationId);
        
        // Add to response headers
        exchange.getResponseHeaders().set(CORRELATION_ID_HEADER, correlationId);
        
        try {
            // Continue the chain
            chain.doFilter(exchange);
        } finally {
            // Clean up MDC
            MDC.remove(MDC_CORRELATION_ID_KEY);
        }
    }
    
    /**
     * Extract correlation ID from request header or generate a new one.
     */
    private String extractOrGenerateCorrelationId(HttpExchange exchange) {
        // Try to get from header
        String correlationId = exchange.getRequestHeaders().getFirst(CORRELATION_ID_HEADER);
        
        if (correlationId == null || !correlationId.matches("[A-Za-z0-9._-]{1,128}")) {
            // Generate new UUID
            correlationId = UUID.randomUUID().toString();
        }
        
        return correlationId;
    }
    
    @Override
    public String description() {
        return "Extracts or generates correlation IDs for request tracing";
    }
}

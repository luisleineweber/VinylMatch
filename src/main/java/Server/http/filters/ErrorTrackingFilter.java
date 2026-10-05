package Server.http.filters;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Local exception boundary. Correlation IDs are supplied by the preceding filter.
 */
public class ErrorTrackingFilter extends Filter {
    
    private static final Logger log = LoggerFactory.getLogger(ErrorTrackingFilter.class);
    
    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        try {
            chain.doFilter(exchange);
        } catch (Exception e) {
            // Log the error with full stack trace
            log.error("Unhandled exception in request {} {}: {}", 
                exchange.getRequestMethod(), 
                exchange.getRequestURI(),
                e.getMessage(), 
                e);
            
            // Send graceful error response if not already sent
            if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                String errorResponse = String.format(
                    "{\"error\": \"internal_error\", \"message\": \"An unexpected error occurred\"}"
                );
                byte[] bytes = errorResponse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(500, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ioe) {
                    // Response already sent, ignore
                }
            }
        }
    }
    
    @Override
    public String description() {
        return "Catches unhandled exceptions and returns a correlated API error";
    }
}

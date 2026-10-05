package Server.http.filters;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;

/**
 * Filter that adds security headers to all HTTP responses.
 * 
 * Headers added:
 * - Strict-Transport-Security (HSTS): Enforces HTTPS
 * - Content-Security-Policy (CSP): Prevents XSS and injection attacks
 * - X-Frame-Options: Prevents clickjacking
 * - X-Content-Type-Options: Prevents MIME type sniffing
 * - Referrer-Policy: Controls referrer information
 * - Permissions-Policy: Restricts browser features
 * 
 * @author VinylMatch Team
 */
public class SecurityHeadersFilter extends Filter {
    
    // CSP Directives
    private static final String CSP_DIRECTIVES = 
        "default-src 'self'; " +
        "script-src 'self'; " +
        "style-src 'self' https://fonts.googleapis.com; " +
        "img-src 'self' data: https: blob:; " +
        "font-src 'self' https://fonts.gstatic.com; " +
        "connect-src 'self' https://*.spotify.com https://api.discogs.com; " +
        "frame-ancestors 'none'; " +
        "base-uri 'self'; " +
        "form-action 'self' https://*.spotify.com https://*.discogs.com;";
    
    // Permissions Policy - restrict browser features
    private static final String PERMISSIONS_POLICY = 
        "accelerometer=(), " +
        "camera=(), " +
        "geolocation=(), " +
        "gyroscope=(), " +
        "magnetometer=(), " +
        "microphone=(), " +
        "payment=(), " +
        "usb=()";
    
    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        var headers = exchange.getResponseHeaders();
        
        // Strict Transport Security (HSTS) - enforce HTTPS for 1 year
        headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload");
        
        // Content Security Policy - prevent XSS
        headers.set("Content-Security-Policy", CSP_DIRECTIVES);
        
        // X-Frame-Options - prevent clickjacking
        headers.set("X-Frame-Options", "DENY");
        
        // X-Content-Type-Options - prevent MIME sniffing
        headers.set("X-Content-Type-Options", "nosniff");
        
        // Referrer Policy - limit referrer information
        headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
        
        // Permissions Policy - restrict browser features
        headers.set("Permissions-Policy", PERMISSIONS_POLICY);
        
        // Continue to next filter/handler
        chain.doFilter(exchange);
    }
    
    @Override
    public String description() {
        return "Adds security headers (HSTS, CSP, X-Frame-Options, etc.) to all responses";
    }
}

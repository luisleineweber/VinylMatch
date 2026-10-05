package Server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared HTTP utilities for request/response handling.
 */
public final class HttpUtils {

    private static final int DEFAULT_MAX_REQUEST_BODY_BYTES = 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_ORIGINS = buildAllowedOrigins();

    private HttpUtils() {}

    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    // =========================================================================
    // Request Parsing
    // =========================================================================

    public static Map<String, String> parseQueryParams(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair == null || pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key;
            String value;
            if (idx >= 0) {
                key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            } else {
                key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                value = "";
            }
            params.put(key, value);
        }
        return params;
    }

    public static String readRequestBody(HttpExchange exchange) throws IOException {
        return readRequestBody(exchange, DEFAULT_MAX_REQUEST_BODY_BYTES);
    }

    public static String readRequestBody(HttpExchange exchange, int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (total + read > maxBytes) {
                    throw new RequestTooLargeException("Request body exceeds " + maxBytes + " bytes");
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    public static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static String stringValue(Object value) {
        if (value instanceof String s) {
            String trimmed = s.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    // =========================================================================
    // Response Helpers
    // =========================================================================

    public static void sendJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    public static void sendApiError(HttpExchange exchange, int statusCode, String code, String message) throws IOException {
        String safeCode = (code == null || code.isBlank()) ? "error" : code.trim();
        String safeMessage = (message == null || message.isBlank()) ? "Request failed" : message;
        sendJson(exchange, statusCode, new ApiErrorResponse(new ApiErrorResponse.ApiError(safeCode, statusCode, safeMessage)));
    }

    public static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        sendApiError(exchange, statusCode, "http_error", message);
    }

    public static void sendText(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] body = (message == null ? "" : message).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    public static void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
    }

    // =========================================================================
    // CORS
    // =========================================================================

    public static void addCorsHeaders(HttpExchange exchange) {
        Headers response = exchange.getResponseHeaders();
        String origin = resolveAllowedOrigin(exchange);
        if (origin != null) {
            response.set("Access-Control-Allow-Origin", origin);
            response.set("Vary", "Origin");
            response.set("Access-Control-Allow-Credentials", "true");
        }
        response.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.set("Access-Control-Allow-Headers", "Content-Type");
    }

    public static boolean handleCorsPreflightIfNeeded(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            addCorsHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        addCorsHeaders(exchange);
        return false;
    }

    private static String resolveAllowedOrigin(HttpExchange exchange) {
        Headers request = exchange.getRequestHeaders();
        String originHeader = request.getFirst("Origin");
        String origin = normalizeOrigin(originHeader);
        if (origin == null) {
            return null;
        }
        String hostHeader = request.getFirst("Host");
        if (hostHeader != null) {
            try {
                URI uri = URI.create(origin);
                String originHost = uri.getHost();
                if (originHost != null && originHost.equalsIgnoreCase(hostHeader.split(":")[0])) {
                    return origin;
                }
            } catch (Exception ignored) {}
        }
        return ALLOWED_ORIGINS.contains(origin) ? origin : null;
    }

    private static String normalizeOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(origin.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            int port = uri.getPort();
            StringBuilder sb = new StringBuilder();
            sb.append(scheme.toLowerCase()).append("://").append(host.toLowerCase());
            if (port > 0 && port != ("https".equalsIgnoreCase(scheme) ? 443 : 80)) {
                sb.append(":").append(port);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<String> buildAllowedOrigins() {
        Set<String> allowed = new HashSet<>();
        allowed.add("http://localhost:3000");
        allowed.add("http://127.0.0.1:3000");
        allowed.add("http://localhost:8888");
        allowed.add("http://127.0.0.1:8888");
        String env = System.getenv("CORS_ALLOWED_ORIGINS");
        if (env != null) {
            for (String part : env.split(",")) {
                String normalized = normalizeOrigin(part);
                if (normalized != null) {
                    allowed.add(normalized);
                }
            }
        }
        return allowed;
    }

    // =========================================================================
    // Security Helpers
    // =========================================================================

    public static boolean isSecureRequest(HttpExchange exchange) {
        return ForwardedRequestResolver.isSecure(exchange);
    }

    public static boolean isDiscogsWebUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return false;
            }
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                return false;
            }
            String lowerHost = host.toLowerCase();
            return lowerHost.equals("discogs.com") || lowerHost.endsWith(".discogs.com");
        } catch (Exception e) {
            return false;
        }
    }

    public static final class RequestTooLargeException extends IOException {
        public RequestTooLargeException(String message) {
            super(message);
        }
    }
}

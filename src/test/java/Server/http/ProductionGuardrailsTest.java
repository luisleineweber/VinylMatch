package Server.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProductionGuardrailsTest {

    @Test
    void observableSecurityBoundaryUsesHardenedCspAndCorrelationId() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.getRequestHeaders().set("X-Correlation-Id", "request-123");
        var chain = new com.sun.net.httpserver.Filter.Chain(
            List.of(), (HttpHandler) ignored -> { throw new IllegalStateException("secret details"); }
        );

        ApiFilters.securityHeaders().doFilter(exchange, chain);

        String csp = exchange.getResponseHeaders().getFirst("Content-Security-Policy");
        assertNotNull(csp);
        assertFalse(csp.contains("unsafe-inline"));
        assertEquals("request-123", exchange.getResponseHeaders().getFirst("X-Correlation-Id"));
        assertEquals(500, exchange.responseCode);
        assertFalse(exchange.responseBody.toString().contains("secret details"));
    }

    @Test
    void rejectsUnsafeInboundCorrelationId() throws Exception {
        FakeExchange exchange = new FakeExchange();
        String oversized = "a".repeat(129);
        exchange.getRequestHeaders().set("X-Correlation-Id", oversized);

        ApiFilters.securityHeaders().doFilter(exchange,
            new com.sun.net.httpserver.Filter.Chain(List.of(), (HttpHandler) ignored -> {}));

        String value = exchange.getResponseHeaders().getFirst("X-Correlation-Id");
        assertNotNull(value);
        assertNotEquals(oversized, value);
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private int responseCode;

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return URI.create("http://127.0.0.1/api/test?token=hidden"); }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int code, long length) { responseCode = code; }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1234); }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8888); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream input, OutputStream output) {}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }
}

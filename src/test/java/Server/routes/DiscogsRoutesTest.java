package Server.routes;

import Server.session.DiscogsSessionStore;
import Server.session.SpotifySessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.DiscogsService;
import com.hctamlyniv.curation.CuratedLinkStore;
import com.hctamlyniv.curation.RedisCuratedLinkStore;
import com.hctamlyniv.discogs.model.CuratedLink;
import com.hctamlyniv.discogs.model.CurationCandidate;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DiscogsRoutesTest {

    @Test
    void candidatesExposeTheStoredVersion(@TempDir Path cacheDir) throws Exception {
        DiscogsService discogs = new DiscogsService("test-token", "test-agent", cacheDir) {
            @Override
            public List<CurationCandidate> fetchCurationCandidates(String artist, String album, Integer year, String title, int limit) {
                return List.of();
            }
        };
        DiscogsRoutes routes = new DiscogsRoutes(() -> discogs, new DiscogsSessionStore(), new SpotifySessionStore());
        Field store = DiscogsRoutes.class.getDeclaredField("curatedLinkStore");
        store.setAccessible(true);
        Method handler = DiscogsRoutes.class.getDeclaredMethod("handleCurationCandidates", HttpExchange.class);
        handler.setAccessible(true);
        ObjectMapper mapper = new ObjectMapper();
        String key = CuratedLinkStore.normalizeKey("Artist", "Album", 2024);
        for (long version : new long[] { 0, 7 }) {
            store.set(routes, new RedisCuratedLinkStore(mapper) {
                @Override
                public Optional<CuratedLink> find(String normalizedKey) {
                    assertEquals(key, normalizedKey);
                    return version == 0 ? Optional.empty() : Optional.of(new CuratedLink(
                            key, "Artist", "Album", 2024, null, null, "https://www.discogs.com/release/1",
                            null, "2026-10-05T00:00:00Z", "manual", version, "admin", "Selected candidate"));
                }
            });
            FakeExchange exchange = new FakeExchange("POST", URI.create("http://127.0.0.1/api/discogs/curation/candidates"));
            exchange.requestBody = "{\"artist\":\"Artist\",\"album\":\"Album\",\"year\":2024}";
            handler.invoke(routes, exchange);
            assertEquals(200, exchange.getResponseCode());
            assertEquals(version, mapper.readTree(exchange.responseBodyAsString()).path("curationVersion").asLong(-1));
        }
    }

    @Test
    void callbackHtmlEscapesUserFacingErrorMessage() throws Exception {
        DiscogsRoutes routes = new DiscogsRoutes(() -> null, new DiscogsSessionStore(), new SpotifySessionStore());
        Method method = DiscogsRoutes.class.getDeclaredMethod("sendOAuthCallbackHtml", HttpExchange.class, boolean.class, String.class);
        method.setAccessible(true);

        FakeExchange exchange = new FakeExchange("GET", URI.create("http://127.0.0.1/api/discogs/oauth/callback"));
        String message = "<script>alert(1)</script>";
        method.invoke(routes, exchange, false, message);

        String body = exchange.responseBodyAsString();
        assertEquals(200, exchange.getResponseCode());
        assertFalse(body.contains(message));
        assertTrue(body.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(body.contains("data-callback-message=\"&lt;script&gt;alert(1)&lt;/script&gt;\""));
        assertTrue(body.contains("data-callback-success=\"false\""));
        assertTrue(body.contains("src=\"/dist/discogs-callback.js\""));
        assertTrue(body.contains("href=\"/styles/discogs-callback.css\""));
        assertFalse(body.contains("<script>"));
        assertFalse(body.contains("<style>"));
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final String method;
        private final URI uri;
        private int responseCode;
        private String requestBody = "";
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

        private FakeExchange(String method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        String responseBodyAsString() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(requestBody.getBytes(StandardCharsets.UTF_8)); }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { this.responseCode = rCode; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1234); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }
}

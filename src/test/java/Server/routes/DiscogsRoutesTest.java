package Server.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.DiscogsService;
import com.hctamlyniv.discogs.JevCandidateRanker;
import com.hctamlyniv.discogs.model.CurationCandidate;
import com.hctamlyniv.discogs.model.CuratedLink;
import com.hctamlyniv.discogs.model.LibraryFlags;
import Server.session.DiscogsSessionStore;
import Server.session.SpotifySessionStore;
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
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DiscogsRoutesTest {

    @TempDir Path tempDir;

    @Test
    void jevRankingDoesNotVerifyOrChangeLibraryState() throws Exception {
        AtomicInteger changes = new AtomicInteger();
        DiscogsService service = new DiscogsService(null, "VinylMatch/Test", tempDir) {
            @Override public List<CurationCandidate> fetchCurationCandidates(String artist, String album,
                    Integer year, String trackTitle, int limit) {
                return List.of(
                        new CurationCandidate(11, "Discovery", "Daft Punk", 2001, null, "Vinyl", null,
                                "https://www.discogs.com/release/11"),
                        new CurationCandidate(22, "Discovery", "Daft Punk", 2001, null, "Vinyl", null,
                                "https://www.discogs.com/release/22"));
            }
            @Override public CuratedLink saveCuratedLink(String artist, String album, Integer year,
                    String track, String barcode, String url, String thumb) {
                changes.incrementAndGet();
                return null;
            }
            @Override public boolean addToWantlist(String username, int releaseId) {
                changes.incrementAndGet();
                return true;
            }
            @Override public Map<Integer, LibraryFlags> lookupLibraryFlags(String username, Set<Integer> ids) {
                changes.incrementAndGet();
                return Map.of();
            }
        };
        JevCandidateRanker ranker = new JevCandidateRanker(new ObjectMapper(), body ->
                new JevCandidateRanker.Response(200, """
                        {"answers":{"discogs_match":{"type":"choice","choice":"22","confidence":0.9,
                        "probabilities":{"11":0.05,"22":0.93,"NO_MATCH":0.01,"REVIEW":0.01}}}}
                        """));
        DiscogsRoutes routes = new DiscogsRoutes(() -> service, new DiscogsSessionStore(),
                new SpotifySessionStore(), true, ranker);
        Method method = DiscogsRoutes.class.getDeclaredMethod("handleCurationCandidates", HttpExchange.class);
        method.setAccessible(true);
        FakeExchange exchange = new FakeExchange("POST", URI.create("http://127.0.0.1/api/discogs/curation/candidates"),
                "{\"artist\":\"Daft Punk\",\"album\":\"Discovery\",\"year\":2001}");

        method.invoke(routes, exchange);

        JsonNode body = new ObjectMapper().readTree(exchange.responseBodyAsString());
        assertEquals(200, exchange.getResponseCode());
        assertEquals(22, body.path("candidates").get(0).path("releaseId").asInt());
        assertEquals("suggested", body.path("jev").path("status").asText());
        assertFalse(body.has("verified"));
        assertFalse(body.has("inWishlist"));
        assertFalse(body.has("inCollection"));
        assertEquals(0, changes.get());
    }

    @Test
    void disabledFeatureDoesNotCallJevOrAddJevMetadata() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DiscogsService service = new DiscogsService(null, "VinylMatch/Test", tempDir) {
            @Override public List<CurationCandidate> fetchCurationCandidates(String artist, String album,
                    Integer year, String trackTitle, int limit) {
                return List.of(new CurationCandidate(11, "Discovery", "Daft Punk", 2001,
                        null, "Vinyl", null, "https://www.discogs.com/release/11"));
            }
        };
        JevCandidateRanker ranker = new JevCandidateRanker(new ObjectMapper(), body -> {
            calls.incrementAndGet();
            return new JevCandidateRanker.Response(200, "{}");
        });
        DiscogsRoutes routes = new DiscogsRoutes(() -> service, new DiscogsSessionStore(),
                new SpotifySessionStore(), false, ranker);
        Method method = DiscogsRoutes.class.getDeclaredMethod("handleCurationCandidates", HttpExchange.class);
        method.setAccessible(true);
        FakeExchange exchange = new FakeExchange("POST", URI.create("http://127.0.0.1/api/discogs/curation/candidates"),
                "{\"artist\":\"Daft Punk\",\"album\":\"Discovery\"}");

        method.invoke(routes, exchange);

        JsonNode body = new ObjectMapper().readTree(exchange.responseBodyAsString());
        assertEquals(200, exchange.getResponseCode());
        assertEquals(1, body.path("candidates").size());
        assertFalse(body.has("jev"));
        assertEquals(0, calls.get());
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
        assertTrue(body.contains("window.opener.postMessage(payload, window.location.origin);"));
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final String method;
        private final URI uri;
        private final byte[] requestBody;
        private int responseCode;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

        private FakeExchange(String method, URI uri) {
            this(method, uri, "");
        }

        private FakeExchange(String method, URI uri, String requestBody) {
            this.method = method;
            this.uri = uri;
            this.requestBody = requestBody.getBytes(StandardCharsets.UTF_8);
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
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(requestBody); }
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

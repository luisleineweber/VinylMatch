package Server.routes;

import Server.auth.SpotifyOAuthService;
import Server.cache.PlaylistCache;
import Server.http.HttpUtils;
import Server.session.SpotifySession;
import Server.session.SpotifySessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class AuthRoutesTest {

    @Test
    void callbackDenialReturnsStructuredSameOriginMessage() throws Exception {
        AuthRoutes routes = new AuthRoutes(new PlaylistCache(new ObjectMapper()), new RecordingSessionStore(), new TestSpotifyOAuthService());
        Method callback = AuthRoutes.class.getDeclaredMethod("handleCallback", HttpExchange.class);
        callback.setAccessible(true);
        FakeExchange exchange = new FakeExchange("GET", URI.create("http://127.0.0.1/api/auth/callback?error=access_denied"));

        callback.invoke(routes, exchange);

        String body = exchange.responseBodyAsString();
        assertEquals(200, exchange.getResponseCode());
        assertTrue(body.contains("<main"));
        assertTrue(body.contains("id=\"spotify-auth-callback\""));
        assertTrue(body.contains("data-auth-success=\"false\""));
        assertTrue(body.contains("data-auth-code=\"spotify_authorization_denied\""));
        assertTrue(body.contains("data-auth-message=\"Spotify authorization was cancelled."));
        assertTrue(body.contains("/styles/spotify-callback.css"));
        assertTrue(body.contains("/dist/spotify-callback.js"));
        assertFalse(body.contains("<style>"));
        assertFalse(body.contains("<script>"));
        assertEquals("no-store", exchange.getResponseHeaders().getFirst("Cache-Control"));
    }

    @Test
    void callbackWithoutSessionExplainsExpiredLogin() throws Exception {
        AuthRoutes routes = new AuthRoutes(new PlaylistCache(new ObjectMapper()), new RecordingSessionStore(), new TestSpotifyOAuthService());
        Method callback = AuthRoutes.class.getDeclaredMethod("handleCallback", HttpExchange.class);
        callback.setAccessible(true);
        FakeExchange exchange = new FakeExchange("GET", URI.create("http://127.0.0.1/api/auth/callback?code=code&state=state"));
        exchange.getRequestHeaders().set("Host", "127.0.0.1");

        callback.invoke(routes, exchange);

        String body = exchange.responseBodyAsString();
        assertTrue(body.contains("data-auth-code=\"spotify_session_missing\""));
        assertTrue(body.contains("login session is missing or expired"));
    }

    @Test
    void callbackPayloadEscapesCodeAndMessage() throws Exception {
        AuthRoutes routes = new AuthRoutes(new PlaylistCache(new ObjectMapper()), new RecordingSessionStore(), new TestSpotifyOAuthService());
        Method sendCallback = AuthRoutes.class.getDeclaredMethod(
            "sendCallbackHtml", HttpExchange.class, boolean.class, String.class, String.class);
        sendCallback.setAccessible(true);
        FakeExchange exchange = new FakeExchange("GET", URI.create("http://127.0.0.1/api/auth/callback"));

        sendCallback.invoke(routes, exchange, false, "bad\"code", "<script>alert(1)</script>");

        String body = exchange.responseBodyAsString();
        assertTrue(body.contains("data-auth-success=\"false\""));
        assertTrue(body.contains("data-auth-code=\"bad&quot;code\""));
        assertTrue(body.contains("data-auth-message=\"&lt;script&gt;alert(1)&lt;/script&gt;\""));
        assertTrue(body.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertFalse(body.contains("<script>alert(1)</script>"));
        assertFalse(body.contains("<style>"));
        assertFalse(body.contains("<script>"));
    }

    @Test
    void getAccessTokenRefreshesAndPersistsSession() {
        RecordingSessionStore sessionStore = new RecordingSessionStore();
        SpotifySession session = expiredSession();
        sessionStore.session = session;

        TestSpotifyOAuthService oauthService = new TestSpotifyOAuthService();
        AuthRoutes routes = new AuthRoutes(new PlaylistCache(new ObjectMapper()), sessionStore, oauthService);

        String token = routes.getAccessToken(new FakeExchange("GET", URI.create("http://127.0.0.1/api/auth/status")));

        assertEquals("refreshed-access-token", token);
        assertEquals(1, sessionStore.storeCount);
        assertEquals("refreshed-access-token", sessionStore.storedSession.getAccessToken());
        assertEquals("refreshed-refresh-token", sessionStore.storedSession.getRefreshToken());
    }


    @Test
    void resolvePlaylistAccessTokenRefreshesAndPersistsSession() {
        RecordingSessionStore sessionStore = new RecordingSessionStore();
        SpotifySession session = expiredSession();
        sessionStore.session = session;

        TestSpotifyOAuthService oauthService = new TestSpotifyOAuthService();
        AuthRoutes routes = new AuthRoutes(new PlaylistCache(new ObjectMapper()), sessionStore, oauthService);

        AuthRoutes.AccessTokenResolution resolution = routes.resolvePlaylistAccessToken(new FakeExchange("GET", URI.create("http://127.0.0.1/api/playlist")));

        assertNotNull(resolution);
        assertTrue(resolution.userAuthenticated());
        assertEquals("refreshed-access-token", resolution.token());
        assertEquals(1, sessionStore.storeCount);
    }

    private static SpotifySession expiredSession() {
        SpotifySession session = new SpotifySession("session-1");
        session.setAccessToken("initial-access-token");
        session.setRefreshToken("initial-refresh-token");
        session.setTokenExpiresAt(System.currentTimeMillis() - 1000);
        return session;
    }

    private static final class RecordingSessionStore extends SpotifySessionStore {
        private SpotifySession session;
        private int storeCount;
        private SpotifySession storedSession;

        @Override
        public SpotifySession getSession(HttpExchange exchange) {
            return session;
        }

        @Override
        public void storeSession(SpotifySession session) {
            storeCount++;
            storedSession = session;
            this.session = session;
        }
    }

    private static final class TestSpotifyOAuthService extends SpotifyOAuthService {
        private TestSpotifyOAuthService() {
            super("client-id", "client-secret", URI.create("http://127.0.0.1/api/auth/callback"));
        }

        @Override
        public boolean refreshAccessToken(SpotifySession session) {
            session.setAccessToken("refreshed-access-token");
            session.setRefreshToken("refreshed-refresh-token");
            session.setTokenExpiresAt(System.currentTimeMillis() + 60_000);
            return true;
        }
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final String method;
        private final URI uri;
        private int responseCode;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

        private FakeExchange(String method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        private String responseBodyAsString() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { responseCode = rCode; }
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

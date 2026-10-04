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
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AuthRoutesTest {

    @Test
    void callbackReturnsImmediatelyAfterSuccessfulLogin() throws Exception {
        AuthRoutes routes = new AuthRoutes(new PlaylistCache(new ObjectMapper()),
                new SpotifySessionStore(), new TestSpotifyOAuthService());
        Method method = AuthRoutes.class.getDeclaredMethod("sendCallbackHtml", HttpExchange.class, boolean.class, String.class);
        method.setAccessible(true);
        FakeExchange exchange = new FakeExchange("GET", URI.create("http://127.0.0.1/api/auth/callback"));

        method.invoke(routes, exchange, true, "Spotify connected.");

        String body = exchange.responseBody.toString(StandardCharsets.UTF_8);
        assertFalse(body.contains("setTimeout"), "Login must not add a fixed wait before returning");
        assertTrue(body.contains("window.location.replace("), "Returning must remove the callback from browser history");
        assertTrue(body.indexOf("<script>") < body.indexOf("<link"), "Return before loading styles and fonts");
    }

    @Test
    void callbackEscapesErrorMessage() throws Exception {
        AuthRoutes routes = new AuthRoutes(new PlaylistCache(new ObjectMapper()),
                new SpotifySessionStore(), new TestSpotifyOAuthService());
        Method method = AuthRoutes.class.getDeclaredMethod("sendCallbackHtml", HttpExchange.class, boolean.class, String.class);
        method.setAccessible(true);
        FakeExchange exchange = new FakeExchange("GET", URI.create("http://127.0.0.1/api/auth/callback"));
        String message = "</script><script>alert('error')</script>";

        method.invoke(routes, exchange, false, message);

        String body = exchange.responseBody.toString(StandardCharsets.UTF_8);
        assertFalse(body.contains(message));
        assertTrue(body.contains("&lt;/script&gt;&lt;script&gt;alert(&#39;error&#39;)&lt;/script&gt;"));
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
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

        private FakeExchange(String method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) {}
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1234); }
        @Override public int getResponseCode() { return 200; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }
}

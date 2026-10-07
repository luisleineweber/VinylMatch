package Server.auth;

import Server.session.SpotifySession;
import org.junit.jupiter.api.Test;
import se.michaelthelin.spotify.SpotifyHttpManager;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpotifyOAuthServiceTest {

    @Test
    void buildsAuthorizationUrlWithState() {
        SpotifyOAuthService svc = new SpotifyOAuthService(
                "client-id",
                "client-secret",
                SpotifyHttpManager.makeUri("http://127.0.0.1:8888/api/auth/callback")
        );

        String url = svc.buildAuthorizationUrl("session-1", null);
        assertNotNull(url);
        assertTrue(url.contains("state="));
        assertTrue(url.contains("scope="));
        assertTrue(url.contains("user-read-currently-playing"));
    }

    @Test
    void exchangeRejectsInvalidStateOrSessionMismatchWithoutNetworkCall() {
        SpotifyOAuthService svc = new SpotifyOAuthService(
                "client-id",
                "client-secret",
                SpotifyHttpManager.makeUri("http://127.0.0.1:8888/api/auth/callback")
        );

        String url = svc.buildAuthorizationUrl("session-1", null);
        String state = parseQuery(URI.create(url)).get("state");
        assertNotNull(state);

        SpotifySession wrongSession = new SpotifySession("other-session");
        assertFalse(svc.exchangeCodeForTokens("code", state, wrongSession));

        SpotifySession session = new SpotifySession("session-1");
        assertFalse(svc.exchangeCodeForTokens("code", "not-a-real-state", session));
    }

    @Test
    void refreshReturnsFalseWhenMissingRefreshToken() {
        SpotifyOAuthService svc = new SpotifyOAuthService(
                "client-id",
                "client-secret",
                SpotifyHttpManager.makeUri("http://127.0.0.1:8888/api/auth/callback")
        );
        SpotifySession session = new SpotifySession("session-1");
        assertFalse(svc.refreshAccessToken(session));
    }

    @Test
    void clientCredentialsTokenReturnsEmptyWhenNotConfigured() {
        SpotifyOAuthService svc = new SpotifyOAuthService(null, null, null);
        assertTrue(svc.getClientCredentialsAccessToken().isEmpty());
    }

    private static Map<String, String> parseQuery(URI uri) {
        String q = uri.getRawQuery();
        if (q == null || q.isBlank()) return Map.of();
        java.util.HashMap<String, String> m = new java.util.HashMap<>();
        for (String part : q.split("&")) {
            int idx = part.indexOf('=');
            if (idx <= 0) continue;
            m.put(part.substring(0, idx), part.substring(idx + 1));
        }
        return m;
    }
}

package Server.auth;

import Server.session.SpotifySession;
import com.hctamlyniv.Config;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.SpotifyHttpManager;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.model_objects.credentials.ClientCredentials;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeUriRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles Spotify OAuth flow for hosted multi-user deployment.
 */
public class SpotifyOAuthService {

    private static final Logger log = LoggerFactory.getLogger(SpotifyOAuthService.class);
    private static final String[] SCOPES = {
            "playlist-read-private",
            "playlist-read-collaborative",
            "user-read-currently-playing"
    };
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    
    // Pending authorization states (state -> session id)
    private final Map<String, PendingAuth> pendingAuthorizations = new ConcurrentHashMap<>();

    private final String clientId;
    private final String clientSecret;
    private final URI redirectUri;
    private final boolean redirectUriExplicit;
    private final SpotifyApi spotifyApi;
    private final Object appTokenLock = new Object();
    private volatile String appAccessToken;
    private volatile long appTokenExpiresAt;

    public SpotifyOAuthService() {
        this.clientId = trimOrNull(Config.getSpotifyClientId());
        this.clientSecret = trimOrNull(Config.getSpotifyClientSecret());
        RedirectUriResolution resolution = resolveRedirectUriFromConfig();
        this.redirectUri = resolution.redirectUri();
        this.redirectUriExplicit = resolution.explicit();

        if (redirectUri != null && isLoopbackUri(redirectUri) && "https".equalsIgnoreCase(redirectUri.getScheme())) {
            log.warn("Spotify redirect URI uses https with a loopback host ({}). For local development, prefer http://127.0.0.1:PORT/api/auth/callback unless you are terminating TLS.", redirectUri);
        }
        
        if (clientId != null && clientSecret != null) {
            this.spotifyApi = new SpotifyApi.Builder()
                    .setClientId(clientId)
                    .setClientSecret(clientSecret)
                    .setRedirectUri(redirectUri)
                    .build();
        } else {
            this.spotifyApi = null;
        }
    }

    /**
     * Test-friendly constructor to avoid relying on process environment variables.
     */
    public SpotifyOAuthService(String clientId, String clientSecret, URI redirectUri) {
        this.clientId = trimOrNull(clientId);
        this.clientSecret = trimOrNull(clientSecret);
        if (redirectUri != null) {
            this.redirectUri = redirectUri;
            this.redirectUriExplicit = true;
        } else {
            RedirectUriResolution resolution = resolveRedirectUriFromConfig();
            this.redirectUri = resolution.redirectUri();
            this.redirectUriExplicit = resolution.explicit();
        }

        if (this.redirectUri != null && isLoopbackUri(this.redirectUri) && "https".equalsIgnoreCase(this.redirectUri.getScheme())) {
            log.warn("Spotify redirect URI uses https with a loopback host ({}). For local development, prefer http://127.0.0.1:PORT/api/auth/callback unless you are terminating TLS.", this.redirectUri);
        }

        if (this.clientId != null && this.clientSecret != null) {
            this.spotifyApi = new SpotifyApi.Builder()
                    .setClientId(this.clientId)
                    .setClientSecret(this.clientSecret)
                    .setRedirectUri(this.redirectUri)
                    .build();
        } else {
            this.spotifyApi = null;
        }
    }

    public boolean isConfigured() {
        return clientId != null && clientSecret != null && spotifyApi != null;
    }

    /**
     * Resolves a Spotify app token using client credentials for anonymous public playlist access.
     */
    public Optional<String> getClientCredentialsAccessToken() {
        if (!isConfigured()) {
            return Optional.empty();
        }

        long now = System.currentTimeMillis();
        String cached = appAccessToken;
        if (cached != null && !cached.isBlank() && now < appTokenExpiresAt) {
            return Optional.of(cached);
        }

        synchronized (appTokenLock) {
            now = System.currentTimeMillis();
            cached = appAccessToken;
            if (cached != null && !cached.isBlank() && now < appTokenExpiresAt) {
                return Optional.of(cached);
            }

            try {
                SpotifyApi api = new SpotifyApi.Builder()
                        .setClientId(clientId)
                        .setClientSecret(clientSecret)
                        .build();
                ClientCredentials credentials = api.clientCredentials().build().execute();
                String token = credentials != null ? credentials.getAccessToken() : null;
                Integer expiresIn = credentials != null ? credentials.getExpiresIn() : null;
                if (token == null || token.isBlank()) {
                    return Optional.empty();
                }

                int safeExpires = Math.max(30, expiresIn != null ? expiresIn : 3600) - 30;
                appAccessToken = token;
                appTokenExpiresAt = System.currentTimeMillis() + (safeExpires * 1000L);
                return Optional.of(token);
            } catch (Exception e) {
                log.warn("Spotify client credentials token request failed: {}", e.getMessage());
                appAccessToken = null;
                appTokenExpiresAt = 0L;
                return Optional.empty();
            }
        }
    }

    /**
     * Generates an authorization URL with CSRF state token.
     * 
     * @param sessionId The session ID to associate with this authorization attempt
     * @param redirectUriOverride Optional override for the redirect URI (used for local dev host consistency)
     * @return The authorization URL to redirect the user to
     */
    public String buildAuthorizationUrl(String sessionId, URI redirectUriOverride) {
        if (!isConfigured()) {
            throw new IllegalStateException("Spotify credentials not configured");
        }

        URI effectiveRedirectUri = redirectUri;
        if (!redirectUriExplicit && redirectUriOverride != null) {
            effectiveRedirectUri = redirectUriOverride;
        }
        log.info("Using redirect URI: {}", effectiveRedirectUri);
        SpotifyApi api = apiForRedirectUri(effectiveRedirectUri);

        // Generate a secure random state token for CSRF protection
        byte[] stateBytes = new byte[24];
        SECURE_RANDOM.nextBytes(stateBytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);

        // Store pending authorization
        pendingAuthorizations.put(state, new PendingAuth(sessionId, System.currentTimeMillis(), effectiveRedirectUri));
        
        // Clean up old pending authorizations (older than 10 minutes)
        long cutoff = System.currentTimeMillis() - (10 * 60 * 1000);
        pendingAuthorizations.entrySet().removeIf(entry -> entry.getValue().createdAt() < cutoff);

        AuthorizationCodeUriRequest uriRequest = api.authorizationCodeUri()
                .scope(String.join(" ", SCOPES))
                .state(state)
                .build();

        return uriRequest.execute().toString();
    }

    /**
     * Exchanges an authorization code for tokens and populates the session.
     * 
     * @param code The authorization code from the callback
     * @param state The state token for CSRF validation
     * @param session The session to populate with tokens
     * @return true if successful
     */
    public boolean exchangeCodeForTokens(String code, String state, SpotifySession session) {
        if (!isConfigured()) {
            log.error("Spotify OAuth not configured - clientId or clientSecret missing");
            return false;
        }

        // Validate state token
        PendingAuth pending = pendingAuthorizations.remove(state);
        if (pending == null) {
            log.warn("Invalid or expired OAuth state token. Available states: {}", pendingAuthorizations.keySet());
            return false;
        }
        
        log.info("Found pending auth for session: {}, redirectUri: {}", pending.sessionId(), pending.redirectUri());

        // Verify session ID matches
        if (!pending.sessionId().equals(session.getSessionId())) {
            log.warn("OAuth session id mismatch - pending: {}, current: {}", pending.sessionId(), session.getSessionId());
            return false;
        }

        try {
            SpotifyApi api = apiForRedirectUri(pending.redirectUri());
            log.info("Exchanging code for tokens with redirect URI: {}", pending.redirectUri());
            AuthorizationCodeCredentials credentials = api.authorizationCode(code).build().execute();

            session.setAccessToken(credentials.getAccessToken());
            session.setRefreshToken(credentials.getRefreshToken());
            
            // Set expiration with 30-second buffer
            long expiresInMs = Math.max(0, (credentials.getExpiresIn() - 30)) * 1000L;
            session.setTokenExpiresAt(System.currentTimeMillis() + expiresInMs);

            // Fetch user ID from Spotify API
            String userId = fetchCurrentUserId(credentials.getAccessToken());
            if (userId != null && !userId.isBlank()) {
                session.setUserId(userId);
                log.info("Spotify user ID obtained: {}", userId);
            } else {
                log.warn("Could not fetch Spotify user ID - token may be invalid");
            }

            log.info("Spotify OAuth tokens obtained successfully");
            return true;
        } catch (Exception e) {
            log.error("Spotify OAuth token exchange failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Fetches the current user's Spotify user ID using the access token.
     */
    private String fetchCurrentUserId(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        try {
            SpotifyApi api = new SpotifyApi.Builder()
                    .setAccessToken(accessToken)
                    .build();
            
            var user = api.getCurrentUsersProfile().build().execute();
            return user != null ? user.getId() : null;
        } catch (Exception e) {
            log.warn("Failed to fetch Spotify user profile: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Refreshes the access token for a session.
     * 
     * @param session The session whose token should be refreshed
     * @return true if successful
     */
    public boolean refreshAccessToken(SpotifySession session) {
        if (!isConfigured() || session == null) {
            return false;
        }

        String refreshToken = session.getRefreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            return false;
        }

        try {
            SpotifyApi api = new SpotifyApi.Builder()
                    .setClientId(clientId)
                    .setClientSecret(clientSecret)
                    .setRefreshToken(refreshToken)
                    .build();

            AuthorizationCodeCredentials credentials = api.authorizationCodeRefresh().build().execute();
            
            session.setAccessToken(credentials.getAccessToken());
            
            // Some refreshes return a new refresh token
            String newRefreshToken = credentials.getRefreshToken();
            if (newRefreshToken != null && !newRefreshToken.isBlank()) {
                session.setRefreshToken(newRefreshToken);
            }
            
            long expiresInMs = Math.max(0, (credentials.getExpiresIn() - 30)) * 1000L;
            session.setTokenExpiresAt(System.currentTimeMillis() + expiresInMs);

            return true;
        } catch (Exception e) {
            log.warn("Spotify OAuth token refresh failed", e);
            return false;
        }
    }

    /**
     * Ensures the session has a valid (non-expired) access token.
     * Refreshes if needed.
     */
    public boolean ensureValidToken(SpotifySession session) {
        if (session == null || !session.isLoggedIn()) {
            return false;
        }

        if (session.isTokenExpired()) {
            return refreshAccessToken(session);
        }

        return true;
    }

    public URI getRedirectUri() {
        return redirectUri;
    }

    public boolean isRedirectUriExplicit() {
        return redirectUriExplicit;
    }

    private SpotifyApi apiForRedirectUri(URI effectiveRedirectUri) {
        if (spotifyApi != null && effectiveRedirectUri != null && effectiveRedirectUri.equals(redirectUri)) {
            return spotifyApi;
        }
        return new SpotifyApi.Builder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRedirectUri(effectiveRedirectUri != null ? effectiveRedirectUri : redirectUri)
                .build();
    }

    private static RedirectUriResolution resolveRedirectUriFromConfig() {
        // Check for explicit redirect URI from config/env
        String configuredUri = Config.getSpotifyRedirectUri();
        if (configuredUri != null && !configuredUri.isBlank()) {
            return new RedirectUriResolution(SpotifyHttpManager.makeUri(configuredUri.trim()), true);
        }

        // Check for public base URL
        String publicBaseUrl = Config.getPublicBaseUrl();
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            String base = publicBaseUrl.trim();
            if (!base.endsWith("/")) {
                base += "/";
            }
            return new RedirectUriResolution(SpotifyHttpManager.makeUri(base + "api/auth/callback"), true);
        }

        // Default to 127.0.0.1 (localhost may not be recognized as secure in some browsers)
        int port = Config.getPort();
        return new RedirectUriResolution(SpotifyHttpManager.makeUri("http://127.0.0.1:" + port + "/api/auth/callback"), false);
    }

    private static boolean isLoopbackUri(URI uri) {
        if (uri == null) return false;
        String host = uri.getHost();
        if (host == null) return false;
        String lowerHost = host.toLowerCase();
        return lowerHost.equals("localhost") || lowerHost.equals("127.0.0.1") || lowerHost.equals("::1");
    }

    private static String trimOrNull(String s) {
        return (s == null) ? null : s.trim();
    }

    private record PendingAuth(String sessionId, long createdAt, URI redirectUri) {}

    private record RedirectUriResolution(URI redirectUri, boolean explicit) {}
}

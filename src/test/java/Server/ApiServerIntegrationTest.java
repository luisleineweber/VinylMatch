package Server;

import Server.http.ApiErrorResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiServerIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private com.sun.net.httpserver.HttpServer server;
    private int port;

    @BeforeAll
    void startServer() throws Exception {
        server = Server.ApiServer.start(0);
        port = server.getAddress().getPort();
        assertTrue(port > 0);
        // Give server time to start accepting connections
        Thread.sleep(100);
    }

    @AfterAll
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void authStatusReturnsLoggedOutByDefault() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(uri("/api/auth/status"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(200, resp.statusCode());
        JsonNode json = mapper.readTree(resp.body());
        assertTrue(json.has("loggedIn"));
        assertFalse(json.get("loggedIn").asBoolean());
    }

    @Test
    void authLoginReturnsAuthorizeUrlOr503WhenNotConfigured() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(uri("/api/auth/login"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (resp.statusCode() == 503) {
            ApiErrorResponse parsed = mapper.readValue(resp.body(), ApiErrorResponse.class);
            assertEquals("spotify_not_configured", parsed.error().code());
            return;
        }

        assertEquals(200, resp.statusCode());
        JsonNode json = mapper.readTree(resp.body());
        assertTrue(json.hasNonNull("authorizeUrl"));
    }

    @Test
    void playlistEndpointRejectsInvalidLimitBeforeSpotifyResolution() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(uri("/api/playlist?id=37i9dQZF1DXcBWIGoYBM5M&limit=not-a-number"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(400, resp.statusCode());
        ApiErrorResponse parsed = mapper.readValue(resp.body(), ApiErrorResponse.class);
        assertEquals("invalid_limit", parsed.error().code());
    }

    @Test
    void vendorsEndpointReturnsArray() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(uri("/api/config/vendors"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(200, resp.statusCode());
        JsonNode json = mapper.readTree(resp.body());
        assertTrue(json.has("vendors"));
        assertTrue(json.get("vendors").isArray());
    }

    @Test
    void discogsBatchWorksWithoutTokenReturnsFallbackUrls() throws Exception {
        String payload = """
                {
                  "tracks": [
                    { "key": "t0", "index": 0, "artist": "AC/DC", "album": "Back In Black", "releaseYear": 1980, "track": "Hells Bells", "barcode": null },
                    { "key": "t1", "index": 1, "artist": "Daft Punk", "album": "Discovery", "releaseYear": 2001, "track": "One More Time", "barcode": null }
                  ]
                }
                """;

        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(uri("/api/discogs/batch"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(200, resp.statusCode());
        JsonNode json = mapper.readTree(resp.body());
        assertTrue(json.has("results"));
        assertEquals(2, json.get("results").size());
        assertNotNull(json.get("results").get(0).get("url").asText());
        assertTrue(json.get("results").get(0).get("url").asText().contains("discogs.com"));
        assertTrue(json.get("results").get(0).hasNonNull("matchType"));
        assertTrue(json.get("results").get(0).hasNonNull("confidence"));
        assertTrue(json.get("results").get(0).hasNonNull("source"));
        assertTrue(json.get("results").get(0).hasNonNull("reason"));
        assertTrue(json.get("results").get(0).has("vinylFormatConfirmed"));
    }

    @Test
    void discogsSearchValidPayloadReturnsUrlEvenWithoutToken() throws Exception {
        String payload = """
                { "artist": "Daft Punk", "album": "Discovery", "releaseYear": 2001, "track": "One More Time" }
                """;
        HttpResponse<String> resp = postJson("/api/discogs/search", payload);
        assertEquals(200, resp.statusCode());
        JsonNode json = mapper.readTree(resp.body());
        assertTrue(json.has("url"));
        assertTrue(json.get("url").asText().contains("discogs.com"));
        assertTrue(json.hasNonNull("matchType"));
        assertTrue(json.hasNonNull("confidence"));
        assertTrue(json.hasNonNull("source"));
        assertTrue(json.hasNonNull("reason"));
    }

    @Test
    void discogsSearchRejectsInvalidPayload() throws Exception {
        HttpResponse<String> resp = postJson("/api/discogs/search", "{ \"artist\": \"x\" }");
        assertEquals(400, resp.statusCode());
        ApiErrorResponse parsed = mapper.readValue(resp.body(), ApiErrorResponse.class);
        assertEquals("invalid_payload", parsed.error().code());
    }

    @Test
    void discogsStatusShowsLoggedOutByDefault() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(uri("/api/discogs/status"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, resp.statusCode());
        assertFalse(mapper.readTree(resp.body()).path("loggedIn").asBoolean());
    }

    @Test
    void discogsWishlistRequiresLogin() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(uri("/api/discogs/wishlist"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(401, resp.statusCode());
        ApiErrorResponse parsed = mapper.readValue(resp.body(), ApiErrorResponse.class);
        assertEquals("discogs_login_required", parsed.error().code());
    }

    @Test
    void discogsLibraryStatusRejectsInvalidPayload() throws Exception {
        HttpResponse<String> resp = postJson("/api/discogs/library-status", "{ \"urls\": [] }");
        assertEquals(401, resp.statusCode());
        ApiErrorResponse parsed = mapper.readValue(resp.body(), ApiErrorResponse.class);
        assertEquals("discogs_login_required", parsed.error().code());
    }

    @Test
    void discogsBatchRejectsInvalidPayload() throws Exception {
        HttpResponse<String> resp = postJson("/api/discogs/batch", "{ \"tracks\": [] }");
        assertEquals(400, resp.statusCode());
        ApiErrorResponse parsed = mapper.readValue(resp.body(), ApiErrorResponse.class);
        assertEquals("invalid_payload", parsed.error().code());
    }

    @Test
    void discogsCurationCandidatesRejectsEmptyPayload() throws Exception {
        HttpResponse<String> resp = postJson("/api/discogs/curation/candidates", "{ }");
        assertEquals(401, resp.statusCode());
        ApiErrorResponse parsed = mapper.readValue(resp.body(), ApiErrorResponse.class);
        assertEquals("unauthorized", parsed.error().code());
    }

    @Test
    void authLogoutWorksWhenLoggedOut() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(uri("/api/auth/logout"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(204, resp.statusCode());
    }

    @Test
    void authStatusPreflightReturnsNoContent() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(uri("/api/auth/status"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Origin", "http://127.0.0.1:8888")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(204, resp.statusCode());
    }

    @Test
    void authCallbackErrorRendersHtml() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(uri("/api/auth/callback?error=access_denied"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().toLowerCase().contains("<!doctype html"));
    }

    @Test
    void discogsLoginRejectsMissingToken() throws Exception {
        HttpResponse<String> resp = postJson("/api/discogs/login", "{ }");
        assertEquals(400, resp.statusCode());
        ApiErrorResponse parsed = mapper.readValue(resp.body(), ApiErrorResponse.class);
        assertEquals("missing_discogs_token", parsed.error().code());
    }

    @Test
    void discogsCurationCandidatesReturnsEmptyListWhenUnconfigured() throws Exception {
        HttpResponse<String> resp = postJson("/api/discogs/curation/candidates", "{ \"artist\": \"Daft Punk\" }");
        assertEquals(401, resp.statusCode());
        ApiErrorResponse parsed = mapper.readValue(resp.body(), ApiErrorResponse.class);
        assertEquals("unauthorized", parsed.error().code());
    }

    @Test
    void staticFileHandlerReturns404ForMissingFile() throws Exception {
        HttpResponse<String> resp = getWithOptionalRedirect("/does-not-exist.xyz");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void discogsCurationSaveValidUrlWorksWithoutDiscogsToken() throws Exception {
        String payload = """
                {
                  "artist": "Daft Punk",
                  "album": "Discovery",
                  "year": 2001,
                  "trackTitle": "One More Time",
                  "barcode": null,
                  "url": "https://www.discogs.com/release/123-test",
                  "thumb": "https://www.discogs.com/image/1.jpg"
                }
                """;
        HttpResponse<String> resp = postJson("/api/discogs/curation/save", payload);
        assertEquals(401, resp.statusCode());
        ApiErrorResponse parsed = mapper.readValue(resp.body(), ApiErrorResponse.class);
        assertEquals("unauthorized", parsed.error().code());
    }

    @Test
    void discogsCurationSaveRejectsInvalidUrl() throws Exception {
        HttpResponse<String> resp = postJson("/api/discogs/curation/save", "{ \"url\": \"https://example.com\" }");
        assertEquals(401, resp.statusCode());
        ApiErrorResponse parsed = mapper.readValue(resp.body(), ApiErrorResponse.class);
        assertEquals("unauthorized", parsed.error().code());
    }

    @Test
    void playlistEndpointValidatesQueryParams() throws Exception {
        HttpResponse<String> missingId = client.send(
                HttpRequest.newBuilder(uri("/api/playlist"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(400, missingId.statusCode());

        HttpResponse<String> invalidOffset = client.send(
                HttpRequest.newBuilder(uri("/api/playlist?id=abc&offset=not-a-number"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(400, invalidOffset.statusCode());
    }

    @Test
    void staticFileHandlerServesHomePage() throws Exception {
        HttpResponse<String> resp = getWithOptionalRedirect("/home.html");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().toLowerCase().contains("<!doctype html"));
    }

    private HttpResponse<String> getWithOptionalRedirect(String path) throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(uri(path))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (resp.statusCode() == 301 || resp.statusCode() == 302 || resp.statusCode() == 307 || resp.statusCode() == 308) {
            String location = resp.headers().firstValue("Location").orElse(null);
            if (location != null && !location.isBlank()) {
                resp = client.send(
                        HttpRequest.newBuilder(URI.create(location))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
            }
        }
        return resp;
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}

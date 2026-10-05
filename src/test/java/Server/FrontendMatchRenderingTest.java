package Server;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendMatchRenderingTest {

    @Test
    void rendererUsesServerMatchEvidenceInsteadOfUrlShape() throws Exception {
        String renderer = Files.readString(
                Path.of("src/main/frontend/dist/playlist/track-renderer.js"),
                StandardCharsets.UTF_8
        );

        assertTrue(renderer.contains("determineMatchQuality(match)"));
        assertTrue(renderer.contains("case \"EXACT_MASTER\""));
        assertTrue(renderer.contains("Master match"));
        assertTrue(renderer.contains("match.reason"));
        assertTrue(renderer.contains("match.confidence.toLowerCase()"));
        assertTrue(renderer.contains("formatMatchSource(match)"));
        assertTrue(renderer.contains("? \"Discogs match\""));
        assertTrue(renderer.contains("match-evidence-toggle"));
        assertTrue(renderer.contains("aria-expanded"));
        assertTrue(renderer.contains("aria-controls"));
        assertTrue(renderer.contains("setMatchEvidenceOpen(false)"));
        assertFalse(renderer.contains("label: \"Direct match\""));
        assertFalse(renderer.contains("normalized.includes(\"/release/\")"));
    }

    @Test
    void playlistPageExplainsLoadedStateSeparatelyFromDiscogsReview() throws Exception {
        String coordinator = Files.readString(
                Path.of("src/main/frontend/dist/playlist.js"),
                StandardCharsets.UTF_8
        );
        String renderer = Files.readString(
                Path.of("src/main/frontend/dist/playlist/track-renderer.js"),
                StandardCharsets.UTF_8
        );

        assertTrue(coordinator.contains("Playlist loaded"));
        assertTrue(coordinator.contains("tracks ready"));
        assertTrue(coordinator.contains("Discogs matching is a separate step"));
        assertTrue(coordinator.contains("updateLoadSummary(aggregated)"));
        assertTrue(renderer.contains("label: \"Verify release\""));
        assertFalse(renderer.contains("label: \"Review needed\""));
    }
}

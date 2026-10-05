package com.hctamlyniv;

import com.hctamlyniv.discogs.model.CuratedLink;
import com.hctamlyniv.discogs.model.DiscogsMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DiscogsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void usesCuratedLinksBeforeApiCalls() {
        DiscogsService service = new DiscogsService("token", "VinylMatch/Test", tempDir);
        CuratedLink link = service.saveCuratedLink(
                "Daft Punk",
                "Discovery",
                2001,
                "One More Time",
                "barcode-1",
                "https://www.discogs.com/release/123-test",
                "https://www.discogs.com/image/1.jpg"
        );
        assertNotNull(link);

        assertEquals(
                "https://www.discogs.com/release/123-test",
                service.findAlbumUri("Daft Punk", "Discovery", 2001, "One More Time", "barcode-1").orElse(null)
        );
        DiscogsMatch match = service.findAlbumMatch("Daft Punk", "Discovery", 2001, "One More Time", "barcode-1").orElseThrow();
        assertEquals("EXACT_RELEASE", match.matchType());
        assertEquals("HIGH", match.confidence());
        assertEquals("MANUAL_CURATION", match.source());
    }

    @Test
    void returnsFallbackWebSearchWithoutToken() {
        DiscogsService service = new DiscogsService(null, "VinylMatch/Test", tempDir);
        String url = service.findAlbumUri("Daft Punk", "Discovery", 2001).orElse(null);
        assertNotNull(url);
        assertTrue(url.contains("discogs.com/search"));
        DiscogsMatch match = service.findAlbumMatch("Daft Punk", "Discovery", 2001, null, null).orElseThrow();
        assertEquals("SEARCH_ONLY", match.matchType());
        assertEquals("LOW", match.confidence());
        assertFalse(match.reason().isBlank());
    }

    @Test
    void cachesFallbackByBarcode() {
        DiscogsService service = new DiscogsService(null, "VinylMatch/Test", tempDir);
        String first = service.findAlbumUri("Daft Punk", "Discovery", 2001, "One More Time", "123456789").orElse(null);
        assertNotNull(first);

        // Different query, same barcode should hit barcode cache and return quickly
        String second = service.findAlbumUri("Other", "Other", null, null, "123456789").orElse(null);
        assertEquals(first, second);
    }

    @Test
    void cachesFallbackByArtistAlbumYear() {
        DiscogsService service = new DiscogsService(null, "VinylMatch/Test", tempDir);
        String first = service.findAlbumUri("AC/DC", "Back In Black", 1980).orElse(null);
        assertNotNull(first);
        String second = service.findAlbumUri("AC/DC", "Back In Black", 1980).orElse(null);
        assertEquals(first, second);
    }

    @Test
    void rechecksLegacyCacheEntriesWhenDiscogsApiIsConfigured() {
        DiscogsService service = new DiscogsService("token", "VinylMatch/Test", tempDir);
        DiscogsMatch legacy = DiscogsMatch.legacyCache("https://www.discogs.com/release/99-test");
        DiscogsMatch verified = new DiscogsMatch(
                "https://www.discogs.com/release/100-test",
                "EXACT_RELEASE",
                "HIGH",
                "DISCOGS_CATALOG",
                "Verified catalog match.",
                false
        );

        assertFalse(service.isCacheFinalResult(legacy));
        assertTrue(service.isCacheFinalResult(verified));
    }

    @Test
    void resolvesReleaseIdFromDiscogsUrlsWithoutToken() {
        DiscogsService service = new DiscogsService(null, "VinylMatch/Test", tempDir);
        assertEquals(123, service.resolveReleaseIdFromUrl("https://www.discogs.com/release/123-test").orElse(-1));
        assertEquals(456, service.resolveReleaseIdFromUrl("https://www.discogs.com/master/456-test").orElse(-1));
        assertTrue(service.resolveReleaseIdFromUrl("https://example.com/release/1-test").isEmpty());
    }
}

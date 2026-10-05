package com.hctamlyniv.discogs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.discogs.model.CuratedLink;
import com.hctamlyniv.discogs.model.DiscogsMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DiscogsCacheStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndLoadsAlbumCacheAndBarcodeCache() {
        ObjectMapper mapper = new ObjectMapper();
        DiscogsCacheStore store = new DiscogsCacheStore(tempDir, mapper);
        store.load();

        String key = store.buildCacheKey("AC/DC", "Back In Black", 1980);
        store.rememberResult(key, "https://www.discogs.com/release/1-test", "123456");

        DiscogsCacheStore reloaded = new DiscogsCacheStore(tempDir, mapper);
        reloaded.load();

        assertEquals("https://www.discogs.com/release/1-test", reloaded.peekCachedUri("AC/DC", "Back In Black", 1980, null).orElse(null));
        assertEquals("https://www.discogs.com/release/1-test", reloaded.peekCachedUri(null, null, null, "123456").orElse(null));
        assertEquals("MANUAL_REVIEW", reloaded.peekCachedMatch("AC/DC", "Back In Black", 1980, null).orElseThrow().matchType());
    }

    @Test
    void persistsMatchEvidenceAndLoadsLegacyStringEntries() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DiscogsCacheStore store = new DiscogsCacheStore(tempDir, mapper);
        String key = store.buildCacheKey("Daft Punk", "Discovery", 2001);
        DiscogsMatch match = new DiscogsMatch(
                "https://www.discogs.com/master/123",
                "EXACT_MASTER",
                "HIGH",
                "DISCOGS_CATALOG",
                "Artist, album, and year matched.",
                false
        );
        store.rememberResult(key, match, null);

        DiscogsCacheStore reloaded = new DiscogsCacheStore(tempDir, mapper);
        reloaded.load();
        assertEquals(match, reloaded.peekCachedMatch("Daft Punk", "Discovery", 2001, null).orElseThrow());

        Path legacyDir = tempDir.resolve("legacy");
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("albums.json"), """
                {"entries":{"Legacy|Album|1999":"https://www.discogs.com/release/99"},"barcodes":{}}
                """, StandardCharsets.UTF_8);
        DiscogsCacheStore legacy = new DiscogsCacheStore(legacyDir, mapper);
        legacy.load();
        DiscogsMatch migrated = legacy.peekCachedMatch("Legacy", "Album", 1999, null).orElseThrow();
        assertEquals("MANUAL_REVIEW", migrated.matchType());
        assertEquals("LOW", migrated.confidence());
        assertEquals("LEGACY_CACHE", migrated.source());
    }

    @Test
    void persistsAndLoadsCuratedLinks() {
        ObjectMapper mapper = new ObjectMapper();
        DiscogsCacheStore store = new DiscogsCacheStore(tempDir, mapper);
        store.load();

        CuratedLink saved = store.saveCuratedLink(
                "k",
                "Daft Punk",
                "Discovery",
                2001,
                "One More Time",
                "barcode",
                "https://www.discogs.com/master/1-test",
                "https://www.discogs.com/image/1.jpg"
        );
        assertNotNull(saved);

        DiscogsCacheStore reloaded = new DiscogsCacheStore(tempDir, mapper);
        reloaded.load();
        assertEquals(saved.url(), reloaded.findCuratedLink("k", null).orElse(null));
    }
}

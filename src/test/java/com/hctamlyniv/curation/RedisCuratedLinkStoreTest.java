package com.hctamlyniv.curation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.discogs.model.CuratedLink;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisCuratedLinkStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsLegacyJsonWithVersionOneDefaults() throws Exception {
        CuratedLink link = mapper.readValue("""
                {
                  "cacheKey":"artist|album|2001",
                  "artist":"Artist",
                  "album":"Album",
                  "year":2001,
                  "url":"https://www.discogs.com/release/1",
                  "collectedAt":"2026-01-01T00:00:00Z",
                  "source":"manual"
                }
                """, CuratedLink.class);

        assertEquals(1, link.version());
        assertEquals("legacy", link.updatedBy());
        assertEquals("Imported legacy curation", link.reason());
    }

    @Test
    void createAndUpdateAppendActorReasonAndHistory() {
        RedisCuratedLinkStore store = new RedisCuratedLinkStore(mapper);
        CuratedLink first = store.save(link("https://www.discogs.com/release/1"), 0,
                context("admin-a", "Initial selection", "corr-1"));
        CuratedLink second = store.save(link("https://www.discogs.com/release/2"), 1,
                context("admin-b", "Correct pressing", "corr-2"));

        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertEquals("admin-b", second.updatedBy());
        assertEquals("Correct pressing", second.reason());
        assertEquals(2, store.history(second.cacheKey()).size());
        assertEquals("CREATE", store.history(second.cacheKey()).get(0).action());
        assertEquals("UPDATE", store.history(second.cacheKey()).get(1).action());
        assertEquals("corr-2", store.history(second.cacheKey()).get(1).correlationId());
        assertEquals(first.url(), store.history(second.cacheKey()).get(1).previousValue().url());
    }

    @Test
    void staleExpectedVersionRejectsWithoutOverwriting() {
        RedisCuratedLinkStore store = new RedisCuratedLinkStore(mapper);
        CuratedLink created = store.save(link("https://www.discogs.com/release/1"), 0,
                context("admin", "Initial", "corr-1"));

        CurationVersionConflictException conflict = assertThrows(
                CurationVersionConflictException.class,
                () -> store.save(link("https://www.discogs.com/release/2"), 0,
                        context("admin", "Stale edit", "corr-2"))
        );

        assertEquals(0, conflict.expectedVersion());
        assertEquals(1, conflict.actualVersion());
        assertEquals(created.url(), store.find(created.cacheKey()).orElseThrow().url());
        assertEquals(1, store.history(created.cacheKey()).size());
    }

    @Test
    void rollbackRestoresOldValueAsANewAuditedVersion() {
        RedisCuratedLinkStore store = new RedisCuratedLinkStore(mapper);
        CuratedLink first = store.save(link("https://www.discogs.com/release/1"), 0,
                context("admin-a", "Initial", "corr-1"));
        store.save(link("https://www.discogs.com/release/2"), 1,
                context("admin-b", "Update", "corr-2"));

        CuratedLink rolledBack = store.rollback(first.cacheKey(), 1, 2,
                context("admin-c", "Undo incorrect pressing", "corr-3"));

        assertEquals(3, rolledBack.version());
        assertEquals(first.url(), rolledBack.url());
        assertEquals("admin-c", rolledBack.updatedBy());
        CurationAuditEvent event = store.history(first.cacheKey()).get(2);
        assertEquals("ROLLBACK", event.action());
        assertEquals(1L, event.restoredFromVersion());
        assertEquals("corr-3", event.correlationId());
        assertEquals(2, event.previousValue().version());
        assertEquals(3, event.newValue().version());
    }

    @Test
    void rollbackRejectsUnknownTargetVersion() {
        RedisCuratedLinkStore store = new RedisCuratedLinkStore(mapper);
        CuratedLink created = store.save(link("https://www.discogs.com/release/1"), 0,
                context("admin", "Initial", "corr"));

        assertThrows(CurationVersionNotFoundException.class,
                () -> store.rollback(created.cacheKey(), 99, 1,
                        context("admin", "Invalid undo", "corr-2")));
        assertEquals(1, store.find(created.cacheKey()).orElseThrow().version());
    }

    private static CuratedLink link(String url) {
        return new CuratedLink(
                "artist|album|2001", "Artist", "Album", 2001, "Track", null,
                url, null, "2026-01-01T00:00:00Z", "manual"
        );
    }

    private static CurationAuditContext context(String actor, String reason, String correlationId) {
        return new CurationAuditContext(actor, reason, correlationId);
    }
}

package com.hctamlyniv.discogs.model;

public record CuratedLink(
        String cacheKey,
        String artist,
        String album,
        Integer year,
        String trackTitle,
        String barcode,
        String url,
        String thumb,
        String collectedAt,
        String source,
        long version,
        String updatedBy,
        String reason
) {
    public CuratedLink {
        version = version < 1 ? 1 : version;
        updatedBy = updatedBy == null || updatedBy.isBlank() ? "legacy" : updatedBy;
        reason = reason == null || reason.isBlank() ? "Imported legacy curation" : reason;
    }

    /** Keeps source and persisted JSON callers compatible with pre-audit curated links. */
    public CuratedLink(String cacheKey, String artist, String album, Integer year, String trackTitle,
                       String barcode, String url, String thumb, String collectedAt, String source) {
        this(cacheKey, artist, album, year, trackTitle, barcode, url, thumb, collectedAt, source,
                1, "legacy", "Imported legacy curation");
    }

    public CuratedLink asVersion(long nextVersion, String actor, String changeReason, String timestamp) {
        return new CuratedLink(cacheKey, artist, album, year, trackTitle, barcode, url, thumb,
                timestamp, source, nextVersion, actor, changeReason);
    }
}

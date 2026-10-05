package com.hctamlyniv.curation;

import com.hctamlyniv.discogs.model.CuratedLink;

public record CurationAuditEvent(
        String cacheKey,
        long version,
        String action,
        String actor,
        String reason,
        String timestamp,
        String correlationId,
        CuratedLink previousValue,
        CuratedLink newValue,
        Long restoredFromVersion
) {}

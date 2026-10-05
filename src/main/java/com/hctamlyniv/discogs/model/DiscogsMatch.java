package com.hctamlyniv.discogs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Discogs destination together with the server-side evidence used to select it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscogsMatch(
        String url,
        String matchType,
        String confidence,
        String source,
        String reason,
        boolean vinylFormatConfirmed
) {
    public static DiscogsMatch legacyCache(String url) {
        return new DiscogsMatch(
                url,
                "MANUAL_REVIEW",
                "LOW",
                "LEGACY_CACHE",
                "Saved before match evidence was recorded; verify the release on Discogs.",
                false
        );
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("url", url);
        values.put("matchType", matchType);
        values.put("confidence", confidence);
        values.put("source", source);
        values.put("reason", reason);
        values.put("vinylFormatConfirmed", vinylFormatConfirmed);
        return values;
    }
}

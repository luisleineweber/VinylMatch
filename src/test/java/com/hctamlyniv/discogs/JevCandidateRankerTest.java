package com.hctamlyniv.discogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.discogs.model.CurationCandidate;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JevCandidateRankerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<CurationCandidate> CANDIDATES = List.of(
            new CurationCandidate(11, "Discovery", "Daft Punk", 2001, "France", "Vinyl",
                    "https://www.discogs.com/image/11.jpg", "https://www.discogs.com/release/11-discovery"),
            new CurationCandidate(22, "Discovery", "Daft Punk", 2001, "Europe", "CD",
                    "https://www.discogs.com/image/22.jpg", "https://www.discogs.com/release/22-discovery")
    );

    private static JevCandidateRanker ranker(String response) {
        return new JevCandidateRanker(MAPPER, body -> new JevCandidateRanker.Response(200, response));
    }

    private static JevCandidateRanker.Ranking rank(JevCandidateRanker ranker) {
        return ranker.rank("Daft Punk", "Discovery", 2001, "One More Time", CANDIDATES);
    }

    private static String answer(String choice, double confidence, double first, double second,
                                 double noMatch, double review) {
        return """
                {"model":"jev-1.13.0","answers":{"discogs_match":{"type":"choice",
                "choice":"%s","confidence":%s,"probabilities":{"11":%s,"22":%s,
                "NO_MATCH":%s,"REVIEW":%s}}},"usage":{"input_tokens":100,"output_tokens":20}}
                """.formatted(choice, confidence, first, second, noMatch, review);
    }

    @Test
    void validChoiceMovesOnlySuppliedCandidateAndSendsOnlyMatchData() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        JevCandidateRanker ranker = new JevCandidateRanker(MAPPER, body -> {
            request.set(body);
            return new JevCandidateRanker.Response(200, answer("22", 0.84, 0.1, 0.88, 0.01, 0.01));
        });

        JevCandidateRanker.Ranking result = rank(ranker);
        assertEquals("suggested", result.jev().status());
        assertEquals(22, result.jev().releaseId());
        assertEquals(List.of(22, 11), result.candidates().stream().map(CurationCandidate::releaseId).toList());
        assertEquals(List.of(11, 22), CANDIDATES.stream().map(CurationCandidate::releaseId).toList());

        JsonNode sent = MAPPER.readTree(request.get());
        assertEquals("jev-latest", sent.path("model").asText());
        assertEquals("Daft Punk", sent.path("state").path("artist").asText());
        assertEquals("One More Time", sent.path("state").path("trackTitle").asText());
        assertEquals(4, sent.path("state").size());
        assertEquals("Discovery", sent.path("questions").path("discogs_match")
                .path("criteria").path("22").path("title").asText());
        assertEquals("CD", sent.path("questions").path("discogs_match")
                .path("criteria").path("22").path("format").asText());
        assertTrue(sent.path("questions").path("discogs_match").path("criteria").has("NO_MATCH"));
        assertTrue(sent.path("questions").path("discogs_match").path("criteria").has("REVIEW"));
        assertFalse(request.get().contains("image/"));
        assertFalse(request.get().contains("spotify"));
        assertFalse(request.get().contains("token"));
        assertFalse(request.get().contains("wishlist"));
    }

    @Test
    void noMatchLeavesAllCandidatesForReview() {
        var result = rank(ranker(answer("NO_MATCH", 0.9, 0.02, 0.03, 0.93, 0.02)));
        assertEquals("no_match", result.jev().status());
        assertNull(result.jev().releaseId());
        assertEquals(CANDIDATES, result.candidates());
    }

    @Test
    void reviewLeavesAllCandidatesForReview() {
        var result = rank(ranker(answer("REVIEW", 0.8, 0.05, 0.05, 0.02, 0.88)));
        assertEquals("review", result.jev().status());
        assertEquals(CANDIDATES, result.candidates());
    }

    @Test
    void lowConfidenceDoesNotReorderOrVerify() {
        var result = rank(ranker(answer("22", 0.2, 0.39, 0.4, 0.11, 0.1)));
        assertEquals("low_confidence", result.jev().status());
        assertEquals(CANDIDATES, result.candidates());
        assertTrue(result.jev().message().contains("Review"));
    }

    @Test
    void idOutsideCandidateListIsRejected() {
        var result = rank(ranker(answer("999", 0.9, 0.02, 0.03, 0.93, 0.02)));
        assertEquals("error", result.jev().status());
        assertTrue(result.jev().message().contains("invalid"));
        assertEquals(CANDIDATES, result.candidates());
    }

    @Test
    void malformedResponseIsRejected() {
        var result = rank(ranker("{\"answers\":{\"discogs_match\":{\"type\":\"choice\",\"choice\":\"22\"}}}"));
        assertEquals("error", result.jev().status());
        assertTrue(result.jev().message().contains("invalid"));
        assertEquals(CANDIDATES, result.candidates());
    }

    @Test
    void malformedJsonIsRejected() {
        var result = rank(ranker("{not-json"));
        assertEquals("error", result.jev().status());
        assertTrue(result.jev().message().contains("invalid"));
        assertEquals(CANDIDATES, result.candidates());
    }

    @Test
    void nonSuccessStatusStaysInManualReview() {
        JevCandidateRanker ranker = new JevCandidateRanker(MAPPER, body ->
                new JevCandidateRanker.Response(503, "sensitive service detail"));
        var result = rank(ranker);
        assertEquals("error", result.jev().status());
        assertFalse(result.jev().message().contains("sensitive"));
        assertEquals(CANDIDATES, result.candidates());
    }

    @Test
    void serviceFailureStaysVisibleWithoutLeakingExceptionText() {
        JevCandidateRanker ranker = new JevCandidateRanker(MAPPER, body -> {
            throw new IOException("secret-value");
        });
        var result = rank(ranker);
        assertEquals("error", result.jev().status());
        assertFalse(result.jev().message().contains("secret-value"));
        assertEquals(CANDIDATES, result.candidates());
    }

    @Test
    void deterministicSingleMatchSkipsJev() {
        AtomicInteger calls = new AtomicInteger();
        JevCandidateRanker ranker = new JevCandidateRanker(MAPPER, body -> {
            calls.incrementAndGet();
            return new JevCandidateRanker.Response(200, "{}");
        });
        var result = ranker.rank("Daft Punk", "Discovery", 2001, null, List.of(CANDIDATES.get(0)));
        assertEquals("skipped", result.jev().status());
        assertEquals(0, calls.get());
    }

    @Test
    void exactDiscogsTitleSkipsJevWhenArtistIsNotSeparate() {
        AtomicInteger calls = new AtomicInteger();
        JevCandidateRanker ranker = new JevCandidateRanker(MAPPER, body -> {
            calls.incrementAndGet();
            return new JevCandidateRanker.Response(200, "{}");
        });
        CurationCandidate candidate = new CurationCandidate(1743771, "Trash80 - Icarus", null,
                null, "US", "CDr", null, "https://www.discogs.com/release/1743771");

        var result = ranker.rank("Trash80", "Icarus", 2008, null, List.of(candidate));

        assertEquals("skipped", result.jev().status());
        assertEquals(0, calls.get());
    }

    @Test
    void uniqueExactMatchAmongCandidatesSkipsJev() {
        AtomicInteger calls = new AtomicInteger();
        JevCandidateRanker ranker = new JevCandidateRanker(MAPPER, body -> {
            calls.incrementAndGet();
            return new JevCandidateRanker.Response(200, "{}");
        });
        CurationCandidate different = new CurationCandidate(33, "Homework", "Daft Punk", 1997,
                null, "Vinyl", null, "https://www.discogs.com/release/33");
        var result = ranker.rank("Daft Punk", "Discovery", 2001, null, List.of(CANDIDATES.get(0), different));
        assertEquals("skipped", result.jev().status());
        assertEquals(0, calls.get());
    }

    @Test
    void uncertainSingleMatchAsksJev() {
        AtomicInteger calls = new AtomicInteger();
        JevCandidateRanker ranker = new JevCandidateRanker(MAPPER, body -> {
            calls.incrementAndGet();
            return new JevCandidateRanker.Response(200, """
                    {"answers":{"discogs_match":{"type":"choice","choice":"REVIEW",
                    "confidence":0.9,"probabilities":{"22":0.02,"NO_MATCH":0.03,"REVIEW":0.95}}}}
                    """);
        });
        CurationCandidate uncertain = new CurationCandidate(22, "Discovery (Deluxe)", "Daft Punk", 2002,
                null, "CD", null, "https://www.discogs.com/release/22");
        var result = ranker.rank("Daft Punk", "Discovery", 2001, null, List.of(uncertain));
        assertEquals("review", result.jev().status());
        assertEquals(1, calls.get());
    }
}

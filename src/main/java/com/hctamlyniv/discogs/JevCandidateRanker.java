package com.hctamlyniv.discogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.hctamlyniv.discogs.model.CurationCandidate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ranks Discogs search results for a person to review. It never saves a match. */
public final class JevCandidateRanker {
    private static final URI API_URI = URI.create("https://api.typesafe.ai/v1/systemone");
    private static final String QUESTION_ID = "discogs_match";
    private static final double MIN_RANKING_CONFIDENCE = 0.5;

    @FunctionalInterface
    public interface Transport {
        Response evaluate(String requestBody) throws IOException, InterruptedException;
    }

    public record Response(int status, String body) {}

    public record Decision(String status, Integer releaseId, Double confidence, String message) {}

    public record Ranking(List<CurationCandidate> candidates, Decision jev) {}

    private final ObjectMapper mapper;
    private final Transport transport;

    public JevCandidateRanker(String apiKey) {
        this(new ObjectMapper(), httpTransport(apiKey));
    }

    public JevCandidateRanker(ObjectMapper mapper, Transport transport) {
        this.mapper = mapper;
        this.transport = transport;
    }

    public Ranking rank(String artist, String album, Integer year, String trackTitle, List<CurationCandidate> candidates) {
        List<CurationCandidate> original = List.copyOf(candidates);
        List<CurationCandidate> eligible = eligibleCandidates(original);
        if (!original.isEmpty() && eligible.isEmpty()) {
            return failure(original, "Jev could not rank these candidates because Discogs release IDs are missing. Review them yourself.");
        }
        long exactMatches = eligible.stream().filter(candidate -> isExactMatch(artist, album, year, candidate)).count();
        if (eligible.isEmpty() || exactMatches == 1) {
            return new Ranking(original, new Decision("skipped", null, null, null));
        }
        try {
            String request = buildRequest(artist, album, year, trackTitle, eligible);
            Response response = transport.evaluate(request);
            if (response.status() != 200) {
                return failure(original, "Jev is unavailable. Review the Discogs candidates yourself.");
            }
            if (response.body() == null || response.body().isBlank()) return invalid(original);
            return readDecision(original, eligible, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(original, "Jev is unavailable. Review the Discogs candidates yourself.");
        } catch (JsonProcessingException e) {
            return invalid(original);
        } catch (Exception e) {
            // Do not log the exception: an HTTP client can include request details in it.
            return failure(original, "Jev is unavailable. Review the Discogs candidates yourself.");
        }
    }

    private static Transport httpTransport(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return body -> new Response(503, "");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return body -> {
            HttpRequest request = HttpRequest.newBuilder(API_URI)
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            for (int attempt = 0; ; attempt++) {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if ((response.statusCode() != 429 && response.statusCode() != 529) || attempt == 2) {
                    return new Response(response.statusCode(), response.body());
                }
                Thread.sleep(500L << attempt);
            }
        };
    }

    private String buildRequest(String artist, String album, Integer year, String trackTitle,
                                List<CurationCandidate> candidates) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        if (artist != null) state.put("artist", artist);
        if (album != null) state.put("album", album);
        if (year != null) state.put("year", year);
        if (trackTitle != null && !trackTitle.isBlank()) state.put("trackTitle", trackTitle);

        Map<String, Object> criteria = new LinkedHashMap<>();
        for (CurationCandidate candidate : candidates) {
            Map<String, Object> release = new LinkedHashMap<>();
            if (candidate.title() != null) release.put("title", candidate.title());
            if (candidate.artist() != null) release.put("artist", candidate.artist());
            if (candidate.year() != null) release.put("year", candidate.year());
            if (candidate.country() != null) release.put("country", candidate.country());
            if (candidate.format() != null) release.put("format", candidate.format());
            criteria.put(candidate.releaseId().toString(), release);
        }
        criteria.put("NO_MATCH", "None of the supplied releases matches the artist and album.");
        criteria.put("REVIEW", "The supplied details are not enough to select one release safely.");

        Map<String, Object> question = Map.of(
                "type", "choice",
                "instructions", "Which Discogs release in the options best matches `artist` and `album`? "
                        + "Use the year and track title when given. Choose NO_MATCH when none fits, "
                        + "or REVIEW when the evidence is unclear. Select only a supplied Discogs ID.",
                "criteria", criteria
        );
        return mapper.writeValueAsString(Map.of(
                "state", state,
                "model", "jev-latest",
                "questions", Map.of(QUESTION_ID, question)
        ));
    }

    private Ranking readDecision(List<CurationCandidate> original, List<CurationCandidate> eligible, String body)
            throws IOException {
        JsonNode root = mapper.readTree(body);
        if (root == null) return invalid(original);
        JsonNode answer = root.path("answers").path(QUESTION_ID);
        JsonNode choiceNode = answer.path("choice");
        JsonNode confidenceNode = answer.path("confidence");
        JsonNode probabilities = answer.path("probabilities");
        Set<String> allowed = new HashSet<>(Set.of("NO_MATCH", "REVIEW"));
        for (CurationCandidate candidate : eligible) allowed.add(candidate.releaseId().toString());

        if (!"choice".equals(answer.path("type").asText(null))
                || !choiceNode.isTextual() || !allowed.contains(choiceNode.asText())
                || !confidenceNode.isNumber() || !validProbability(confidenceNode.asDouble())
                || !probabilities.isObject() || probabilities.size() != allowed.size()) {
            return invalid(original);
        }
        double total = 0;
        double largest = -1;
        for (String option : allowed) {
            JsonNode probability = probabilities.get(option);
            if (probability == null || !probability.isNumber() || !validProbability(probability.asDouble())) {
                return invalid(original);
            }
            total += probability.asDouble();
            largest = Math.max(largest, probability.asDouble());
        }
        if (Math.abs(total - 1) > 0.02 || probabilities.size() != allowed.size()
                || probabilities.get(choiceNode.asText()).asDouble() + 0.000001 < largest) {
            return invalid(original);
        }

        String choice = choiceNode.asText();
        double confidence = confidenceNode.asDouble();
        if ("NO_MATCH".equals(choice)) {
            return new Ranking(original, new Decision("no_match", null, confidence,
                    "Jev suggests no match. Review the candidates yourself."));
        }
        if ("REVIEW".equals(choice)) {
            return new Ranking(original, new Decision("review", null, confidence,
                    "Jev could not choose a release. Review the candidates yourself."));
        }

        int releaseId = Integer.parseInt(choice);
        if (confidence < MIN_RANKING_CONFIDENCE) {
            return new Ranking(original, new Decision("low_confidence", releaseId, confidence,
                    "Jev is unsure. Review all candidates before saving a match."));
        }
        List<CurationCandidate> ranked = new ArrayList<>(original);
        ranked.sort((left, right) -> Boolean.compare(
                !releaseIdEquals(left, releaseId), !releaseIdEquals(right, releaseId)));
        return new Ranking(List.copyOf(ranked), new Decision("suggested", releaseId, confidence,
                "Jev placed one candidate first. Review it before saving a match."));
    }

    private static boolean releaseIdEquals(CurationCandidate candidate, int releaseId) {
        return candidate.releaseId() != null && candidate.releaseId() == releaseId;
    }

    private static boolean validProbability(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private static List<CurationCandidate> eligibleCandidates(List<CurationCandidate> candidates) {
        List<CurationCandidate> eligible = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (CurationCandidate candidate : candidates) {
            Integer id = candidate.releaseId();
            if (id != null && id > 0 && !seen.contains(id)
                    && candidate.url() != null && candidate.url().contains("/release/")
                    && DiscogsUrlUtils.resolveReleaseIdFromUrl(candidate.url()).filter(id::equals).isPresent()) {
                seen.add(id);
                eligible.add(candidate);
            }
        }
        return eligible;
    }

    private static boolean isExactMatch(String artist, String album, Integer year, CurationCandidate candidate) {
        if (artist == null || album == null || candidate.artist() == null || candidate.title() == null) return false;
        if (year != null && !year.equals(candidate.year())) return false;
        String title = candidate.title();
        int separator = title.indexOf(" - ");
        if (separator >= 0) title = title.substring(separator + 3);
        return normalized(artist).equals(normalized(candidate.artist()))
                && normalized(album).equals(normalized(title));
    }

    private static String normalized(String value) {
        return DiscogsNormalizer.normalizeTitleLevel(value, DiscogsNormalizer.NormLevel.LIGHT).toLowerCase();
    }

    private static Ranking invalid(List<CurationCandidate> original) {
        return failure(original, "Jev returned an invalid result. Review the Discogs candidates yourself.");
    }

    private static Ranking failure(List<CurationCandidate> original, String message) {
        return new Ranking(original, new Decision("error", null, null, message));
    }
}

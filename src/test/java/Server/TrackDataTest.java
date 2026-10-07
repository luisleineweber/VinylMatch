package Server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.discogs.model.DiscogsMatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackDataTest {

    @Test
    void preservesDiscogsMatchEvidenceInSerializedPlaylistTrack() throws Exception {
        DiscogsMatch match = new DiscogsMatch(
                "https://www.discogs.com/release/123-test",
                "EXACT_RELEASE",
                "HIGH",
                "DISCOGS_CATALOG",
                "Artist and album matched.",
                false
        );
        TrackData track = new TrackData(
                "spotify-track",
                "Track",
                "Artist",
                "Album",
                2020,
                "https://open.spotify.com/album/album",
                match.url(),
                match,
                "barcode",
                "https://image.test/cover.jpg"
        );

        assertEquals(match, track.getDiscogsMatch());
        String json = new ObjectMapper().writeValueAsString(track);
        assertTrue(json.contains("\"discogsMatch\""));
        assertTrue(json.contains("\"confidence\":\"HIGH\""));
    }
}

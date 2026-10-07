package com.hctamlyniv.spotify;

import Server.PlaylistData;
import Server.TrackData;
import com.hctamlyniv.DiscogsService;
import com.hctamlyniv.discogs.DiscogsNormalizer;
import com.hctamlyniv.discogs.model.DiscogsMatch;
import se.michaelthelin.spotify.model_objects.specification.Album;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;
import se.michaelthelin.spotify.model_objects.specification.Episode;
import se.michaelthelin.spotify.model_objects.specification.Playlist;
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack;
import se.michaelthelin.spotify.model_objects.specification.Track;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles PlaylistData from Spotify API responses.
 */
public class PlaylistAssembler {

    private static final Logger log = LoggerFactory.getLogger(PlaylistAssembler.class);

    private final BarcodeExtractor barcodeExtractor;
    private final DiscogsService discogsService;

    public PlaylistAssembler(DiscogsService discogsService) {
        this.barcodeExtractor = new BarcodeExtractor();
        this.discogsService = discogsService;
    }

    /**
     * Extracts playlist metadata from a Playlist object.
     */
    public PlaylistMetadata extractMetadata(Playlist playlist) {
        String playlistName = playlist.getName();
        String playlistCoverUrl = (playlist.getImages() != null && playlist.getImages().length > 0)
                ? playlist.getImages()[0].getUrl()
                : null;
        String playlistUrl = null;
        if (playlist.getExternalUrls() != null && playlist.getExternalUrls().getExternalUrls() != null) {
            playlistUrl = playlist.getExternalUrls().getExternalUrls().get("spotify");
        }
        return new PlaylistMetadata(playlistName, playlistCoverUrl, playlistUrl);
    }

    /**
     * Converts playlist tracks to TrackData objects.
     * 
     * @param playlistTracks List of playlist tracks
     * @param albumDetailsMap Map of album ID to Album details
     * @return List of TrackData objects
     */
    public List<TrackData> assembleTrackData(List<PlaylistTrack> playlistTracks, Map<String, Album> albumDetailsMap) {
        List<TrackData> tracks = new ArrayList<>();

        for (PlaylistTrack playlistTrack : playlistTracks) {
            Object item = playlistTrack.getItem();
            
            if (item instanceof Track track) {
                TrackData trackData = convertTrack(track, albumDetailsMap);
                if (trackData != null) {
                    tracks.add(trackData);
                }
            } else if (item instanceof Episode episode) {
                log.debug("Skipping podcast episode: {}", episode.getName());
            } else if (item != null) {
                log.debug("Skipping unknown playlist item type: {}", item.getClass().getName());
            }
        }

        return tracks;
    }

    /**
     * Converts a single Track to TrackData.
     */
    private TrackData convertTrack(Track track, Map<String, Album> albumDetailsMap) {
        // Build artist name
        String artistName = (track.getArtists() != null && track.getArtists().length > 0)
                ? Arrays.stream(track.getArtists())
                    .map(ArtistSimplified::getName)
                    .collect(Collectors.joining(", "))
                : "Unknown";

        // Get album details
        String albumId = (track.getAlbum() != null) ? track.getAlbum().getId() : null;
        Album albumDetails = (albumId != null) ? albumDetailsMap.get(albumId) : null;

        // Album name
        String albumName = (albumDetails != null) ? albumDetails.getName()
                : (track.getAlbum() != null ? track.getAlbum().getName() : "Unknown");

        // Release year
        Integer releaseYear = null;
        String releaseDate = null;
        if (albumDetails != null && albumDetails.getReleaseDate() != null) {
            releaseDate = albumDetails.getReleaseDate();
        } else if (track.getAlbum() != null) {
            releaseDate = track.getAlbum().getReleaseDate();
        }
        if (releaseDate != null && releaseDate.length() >= 4) {
            try {
                releaseYear = Integer.parseInt(releaseDate.substring(0, 4));
            } catch (NumberFormatException ignore) {
                // Year remains null
            }
        }

        // Album URL
        String albumUrl = null;
        if (albumDetails != null && albumDetails.getExternalUrls() != null
                && albumDetails.getExternalUrls().getExternalUrls() != null) {
            albumUrl = albumDetails.getExternalUrls().getExternalUrls().get("spotify");
        } else if (track.getAlbum() != null && track.getAlbum().getExternalUrls() != null
                && track.getAlbum().getExternalUrls().getExternalUrls() != null) {
            albumUrl = track.getAlbum().getExternalUrls().getExternalUrls().get("spotify");
        }

        // Cover URL
        String coverUrl = null;
        if (albumDetails != null && albumDetails.getImages() != null && albumDetails.getImages().length > 0) {
            coverUrl = albumDetails.getImages()[0].getUrl();
        } else if (track.getAlbum() != null && track.getAlbum().getImages() != null && track.getAlbum().getImages().length > 0) {
            coverUrl = track.getAlbum().getImages()[0].getUrl();
        }

        // Track name
        String trackName = track.getName();

        // Barcode
        String barcode = barcodeExtractor.getOrExtractBarcode(albumId, albumDetails);

        // Discogs match from cache only, no API call - preserve provenance for the initial render.
        String discogsUrl = null;
        DiscogsMatch discogsMatch = null;
        if (discogsService != null) {
            String normalizedArtist = DiscogsNormalizer.extractPrimaryArtist(artistName);
            String normalizedAlbum = DiscogsNormalizer.normalizeForCacheKey(albumName);
            discogsMatch = discogsService.peekCachedMatch(normalizedArtist, normalizedAlbum, releaseYear, barcode)
                    .orElse(null);
            discogsUrl = discogsMatch == null ? null : discogsMatch.url();
        }

        return new TrackData(track.getId(), trackName, artistName, albumName, releaseYear, albumUrl, discogsUrl, discogsMatch, barcode, coverUrl);
    }

    /**
     * Assembles a complete PlaylistData object.
     */
    public PlaylistData assemblePlaylistData(
            PlaylistMetadata metadata,
            List<TrackData> tracks,
            int total,
            int offset,
            int nextOffset,
            boolean hasMore
    ) {
        return new PlaylistData(
                metadata.name(),
                metadata.coverUrl(),
                metadata.url(),
                tracks,
                total,
                offset,
                nextOffset,
                hasMore
        );
    }

    /**
     * Metadata extracted from a playlist.
     */
    public record PlaylistMetadata(String name, String coverUrl, String url) {}
}

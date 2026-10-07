package com.hctamlyniv.spotify;

import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.specification.Album;
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack;
import se.michaelthelin.spotify.model_objects.specification.Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles batch loading of Spotify album details.
 */
public class SpotifyAlbumBatchLoader {

    private static final int BATCH_SIZE = 20; // Spotify API limit for getSeveralAlbums
    private static final Logger log = LoggerFactory.getLogger(SpotifyAlbumBatchLoader.class);

    private final SpotifyApi spotifyApi;

    public SpotifyAlbumBatchLoader(SpotifyApi spotifyApi) {
        this.spotifyApi = spotifyApi;
    }

    /**
     * Extracts unique album IDs from a list of playlist tracks.
     */
    public Set<String> extractAlbumIds(List<PlaylistTrack> playlistTracks) {
        Set<String> albumIds = new HashSet<>();
        for (PlaylistTrack playlistTrack : playlistTracks) {
            Object item = playlistTrack.getItem();
            if (item instanceof Track track) {
                if (track.getAlbum() != null && track.getAlbum().getId() != null) {
                    albumIds.add(track.getAlbum().getId());
                }
            }
        }
        return albumIds;
    }

    /**
     * Loads album details for a set of album IDs in batches.
     * 
     * @param albumIds Set of album IDs to load
     * @return Map of album ID to Album object
     */
    public Map<String, Album> loadAlbumDetails(Set<String> albumIds) {
        Map<String, Album> albumDetailsMap = new HashMap<>();

        if (albumIds == null || albumIds.isEmpty()) {
            return albumDetailsMap;
        }

        List<String> albumIdList = new ArrayList<>(albumIds);

        for (int i = 0; i < albumIdList.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, albumIdList.size());
            List<String> batch = albumIdList.subList(i, end);

            try {
                Album[] batchAlbums = spotifyApi
                        .getSeveralAlbums(String.join(",", batch))
                        .build()
                        .execute();

                if (batchAlbums != null) {
                    for (Album album : batchAlbums) {
                        if (album != null && album.getId() != null) {
                            albumDetailsMap.put(album.getId(), album);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Spotify album batch load failed (batch {}): {}", (i / BATCH_SIZE + 1), e.getMessage());
            }
        }

        return albumDetailsMap;
    }

    /**
     * Loads album details for all albums referenced in the playlist tracks.
     */
    public Map<String, Album> loadAlbumDetailsForTracks(List<PlaylistTrack> playlistTracks) {
        Set<String> albumIds = extractAlbumIds(playlistTracks);
        return loadAlbumDetails(albumIds);
    }
}

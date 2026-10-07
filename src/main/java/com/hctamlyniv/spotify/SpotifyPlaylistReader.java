package com.hctamlyniv.spotify;

import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.specification.Paging;
import se.michaelthelin.spotify.model_objects.specification.Playlist;
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles reading and paging through Spotify playlist items.
 */
public class SpotifyPlaylistReader {

    private static final int PAGE_SIZE = 50;

    private final SpotifyApi spotifyApi;

    public SpotifyPlaylistReader(SpotifyApi spotifyApi) {
        this.spotifyApi = spotifyApi;
    }

    /**
     * Fetches playlist metadata.
     */
    public Playlist getPlaylist(String playlistId) throws Exception {
        return spotifyApi.getPlaylist(playlistId).build().execute();
    }

    /**
     * Fetches a page of playlist tracks.
     * 
     * @param playlistId The playlist ID
     * @param offset Starting offset
     * @param limit Maximum items per page (max 50)
     * @return Paging object with playlist tracks
     */
    public Paging<PlaylistTrack> getPlaylistItems(String playlistId, int offset, int limit) throws Exception {
        return spotifyApi
                .getPlaylistItems(playlistId)
                .limit(Math.min(limit, PAGE_SIZE))
                .offset(offset)
                .build()
                .execute();
    }

    /**
     * Fetches all playlist tracks with pagination, starting from offset up to limit.
     * 
     * @param playlistId The playlist ID
     * @param requestedOffset Starting offset
     * @param requestedLimit Maximum total items to fetch (-1 for all)
     * @return Result containing tracks and pagination info
     */
    public PlaylistItemsResult getAllPlaylistItems(String playlistId, int requestedOffset, int requestedLimit) throws Exception {
        int safeOffset = Math.max(0, requestedOffset);
        boolean paginated = requestedLimit > 0;
        int remaining = paginated ? requestedLimit : 0;
        int currentOffset = safeOffset;
        int total = 0;

        List<PlaylistTrack> allItems = new ArrayList<>();

        while (true) {
            if (paginated && remaining <= 0) {
                break;
            }

            int requestLimit = paginated ? Math.min(remaining, PAGE_SIZE) : PAGE_SIZE;
            if (requestLimit <= 0) {
                break;
            }

            Paging<PlaylistTrack> page = getPlaylistItems(playlistId, currentOffset, requestLimit);
            PlaylistTrack[] items = page.getItems();
            total = page.getTotal();

            if (items == null || items.length == 0) {
                break;
            }

            for (PlaylistTrack item : items) {
                allItems.add(item);
            }

            currentOffset += items.length;
            if (paginated) {
                remaining -= items.length;
                if (currentOffset >= total) {
                    break;
                }
            } else {
                if (page.getNext() == null) {
                    break;
                }
            }
        }

        boolean hasMore = paginated && currentOffset < total;
        int nextOffset = paginated ? Math.min(currentOffset, total) : currentOffset;

        return new PlaylistItemsResult(allItems, total, safeOffset, nextOffset, hasMore);
    }

    /**
     * Result of fetching playlist items with pagination info.
     */
    public record PlaylistItemsResult(
        List<PlaylistTrack> items,
        int total,
        int offset,
        int nextOffset,
        boolean hasMore
    ) {}
}

package com.hctamlyniv.discogs.model;

import java.util.List;

public final class CatalogData {
    private CatalogData() {}

    public record Item(int id, String kind, String title, String artist, Integer year,
                       String image, String url, String songQuery) {}
    public record Search(List<Item> items) {}
    public record Artist(int id, String name, String image, String url, List<Item> albums,
                         int page, int pages, int total) {}
    public record ArtistLink(int id, String name) {}
    public record Track(String position, String title, String duration) {}
    public record Album(int id, String kind, String title, String artist, Integer year,
                        String image, String url, List<ArtistLink> artists, List<Track> tracks,
                        Integer offers, Boolean vinyl, String marketplaceUrl) {}
}

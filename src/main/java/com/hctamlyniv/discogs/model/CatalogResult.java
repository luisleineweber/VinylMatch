package com.hctamlyniv.discogs.model;

public record CatalogResult<T>(int status, String code, String message, T data) {
    public static <T> CatalogResult<T> success(T data) {
        return new CatalogResult<>(200, null, null, data);
    }

    public static <T> CatalogResult<T> failure(int status, String code, String message) {
        return new CatalogResult<>(status, code, message, null);
    }

    public <U> CatalogResult<U> failure() {
        return failure(status, code, message);
    }
}

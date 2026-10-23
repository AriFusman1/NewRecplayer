package com.pcapplayer.catalog;

/** @param categoryId null for a global tag */
public record Tag(String id, String name, String categoryId) {
}

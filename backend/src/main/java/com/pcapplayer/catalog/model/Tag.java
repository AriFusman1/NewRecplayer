package com.pcapplayer.catalog.model;

/** @param categoryId null for a global tag */
public record Tag(String id, String name, String categoryId) {
}

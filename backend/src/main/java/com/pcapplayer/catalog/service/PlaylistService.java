package com.pcapplayer.catalog.service;

import com.pcapplayer.catalog.model.SavedPlaylist;
import com.pcapplayer.error.ApiException;
import com.pcapplayer.util.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.pcapplayer.catalog.service.CatalogSupport.nameExists;
import static com.pcapplayer.catalog.service.CatalogSupport.nameTaken;
import static com.pcapplayer.catalog.service.CatalogSupport.requireName;
import static com.pcapplayer.catalog.service.CatalogSupport.writeToStorage;

/** Named playlists saved on the server and shared by everyone. */
public class PlaylistService {

    public record PlaylistRequest(String name, List<SavedPlaylist.Item> items, String by) {
    }

    private final CatalogSupport catalog;

    PlaylistService(CatalogSupport catalog) {
        this.catalog = catalog;
    }

    public SavedPlaylist create(PlaylistRequest request) {
        String name = requireName(request.name());
        List<SavedPlaylist.Item> items = validItems(request.items());
        String savedBy = Objects.requireNonNullElse(request.by(), "");
        return catalog.mutate(() -> {
            checkName(name, null);
            long now = System.currentTimeMillis();
            SavedPlaylist playlist = new SavedPlaylist(UUID.randomUUID().toString(), name, items, savedBy, now, savedBy, now);
            writeToStorage(() -> catalog.repo.putPlaylist(playlist));
            return playlist;
        });
    }

    /** Replaces the name and items. */
    public SavedPlaylist update(String id, PlaylistRequest request) {
        String name = requireName(request.name());
        List<SavedPlaylist.Item> items = validItems(request.items());
        return catalog.mutate(() -> {
            SavedPlaylist old = require(id);
            checkName(name, id);
            SavedPlaylist playlist = new SavedPlaylist(id, name, items, old.createdBy(), old.createdAtMs(),
                    Objects.requireNonNullElse(request.by(), ""), System.currentTimeMillis());
            writeToStorage(() -> catalog.repo.putPlaylist(playlist));
            return playlist;
        });
    }

    public void delete(String id) {
        catalog.mutate(() -> {
            require(id);
            writeToStorage(() -> catalog.repo.deletePlaylist(id));
        });
    }

    private SavedPlaylist require(String id) {
        SavedPlaylist playlist = catalog.repo.playlist(id);
        if (playlist == null) throw ApiException.notFound("Playlist");
        return playlist;
    }

    private void checkName(String name, String exceptId) {
        if (nameTaken(catalog.repo.playlists(), SavedPlaylist::name, SavedPlaylist::id, name, exceptId)) {
            throw nameExists("PLAYLIST_EXISTS", name, "Playlist name already used");
        }
    }

    /** Saved items may later point at deleted records (the UI shows them as missing), but not at unknown ones now. */
    private List<SavedPlaylist.Item> validItems(List<SavedPlaylist.Item> items) {
        if (items == null || items.isEmpty()) throw ApiException.unprocessable("EMPTY_PLAYLIST", "The playlist is empty");
        List<SavedPlaylist.Item> out = new ArrayList<>();
        for (SavedPlaylist.Item item : items) {
            if (item == null || item.recordId() == null || catalog.repo.record(item.recordId()) == null) {
                throw ApiException.unprocessable("RECORD_NOT_FOUND", "A record in the playlist no longer exists");
            }
            out.add(new SavedPlaylist.Item(item.recordId(), Strings.blankToNull(item.host()), item.port(),
                    Math.max(0, item.sleepAfterMs())));
        }
        return out;
    }
}

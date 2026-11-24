package com.pcapplayer.recordManagement.service;

import com.pcapplayer.icd.loading.IcdRegistry;
import com.pcapplayer.pcapProcessing.extract.CachingExtractor;
import com.pcapplayer.persistence.FileCache;
import com.pcapplayer.playback.model.HistoryEntry;
import com.pcapplayer.playback.model.ScriptSource;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.recordManagement.model.Record;
import com.pcapplayer.recordManagement.model.SavedPlaylist;
import com.pcapplayer.recordManagement.model.Tag;
import com.pcapplayer.recordManagement.repository.CatalogRepository;
import com.pcapplayer.uploadHandling.UploadService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Entry point to the catalog: builds the services that own each part of it and serves the
 * read-only views (the whole catalog, history).
 * <ul>
 *   <li>{@link #records()}: preview, create, edit and delete records</li>
 *   <li>{@link #taxonomy()}: categories and tags</li>
 *   <li>{@link #playlists()}: saved playlists</li>
 *   <li>{@link #scriptSource()}: records as replay scripts, for the engine</li>
 *   <li>{@link #decoding()}: records decoded with an ICD (debug)</li>
 * </ul>
 */
public class CatalogService {

    public record FileView(String id, String sha256, String originalName, long size, String format,
                           String uploadedBy, long uploadedAtMs, long startUs, long endUs) {
    }

    public record Catalog(List<Category> categories, List<Tag> tags, List<Record> records, List<FileView> files,
                          List<SavedPlaylist> playlists) {
    }

    private final CatalogRepository repo;
    private final RecordService records;
    private final TaxonomyService taxonomy;
    private final PlaylistService playlists;
    private final ScriptSource scriptSource;
    private final RecordDecodeService decoding;

    public CatalogService(CatalogRepository repo, UploadService uploads, FileCache cache, CachingExtractor extractor,
                          IcdRegistry icds, int maxBins, Runnable onChange) {
        CatalogSupport support = new CatalogSupport(repo, onChange);
        this.repo = repo;
        this.records = new RecordService(support, uploads, cache, extractor, maxBins);
        this.taxonomy = new TaxonomyService(support, records);
        this.playlists = new PlaylistService(support);
        this.scriptSource = new RecordScriptSource(repo, cache, extractor, maxBins);
        this.decoding = new RecordDecodeService(support, cache, extractor, icds);
    }

    public RecordService records() {
        return records;
    }

    public TaxonomyService taxonomy() {
        return taxonomy;
    }

    public PlaylistService playlists() {
        return playlists;
    }

    public ScriptSource scriptSource() {
        return scriptSource;
    }

    public RecordDecodeService decoding() {
        return decoding;
    }

    public Catalog catalog() {
        List<FileView> files = repo.files().stream().map(file -> new FileView(file.id(), file.sha256(), file.originalName(),
                file.size(), file.format(), file.uploadedBy(), file.uploadedAtMs(), file.summary().startUs(), file.summary().endUs())).toList();
        return new Catalog(repo.categories(), repo.tags(), repo.records(), files, repo.playlists());
    }

    public List<HistoryEntry> history() {
        return repo.history();
    }

    public void addHistory(HistoryEntry e) {
        try {
            repo.addHistory(e);
        } catch (IOException ioError) {
            throw new UncheckedIOException(ioError);
        }
    }
}

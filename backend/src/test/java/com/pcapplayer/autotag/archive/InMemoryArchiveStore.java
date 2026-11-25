package com.pcapplayer.autotag.archive;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/** A fake archive: objects in memory, with an optional delay per read (to test cancelling). */
public class InMemoryArchiveStore implements ArchiveStore {

    private record Entry(byte[] data, long lastModifiedUs) {
    }

    private final Map<String, Entry> objects = new TreeMap<>();
    public final AtomicInteger opened = new AtomicInteger();
    public volatile long readDelayMs;

    public InMemoryArchiveStore put(String key, byte[] data, long lastModifiedUs) {
        objects.put(key, new Entry(data, lastModifiedUs));
        return this;
    }

    @Override
    public ArchiveFolder browse(String prefix) {
        TreeSet<String> folders = new TreeSet<>();
        List<ArchiveObject> files = new ArrayList<>();

        for (String key : objects.keySet()) {
            if (!key.startsWith(prefix)) continue;
            String rest = key.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash >= 0) folders.add(prefix + rest.substring(0, slash + 1));
            else files.add(object(key));
        }
        return new ArchiveFolder(prefix, List.copyOf(folders), ArchiveSelection.filter(files, null));
    }

    @Override
    public List<ArchiveObject> list(String prefix, boolean recursive) {
        List<ArchiveObject> result = new ArrayList<>();

        for (String key : objects.keySet()) {
            if (!key.startsWith(prefix)) continue;
            if (!recursive && key.substring(prefix.length()).contains("/")) continue;
            result.add(object(key));
        }
        return result;
    }

    @Override
    public ArchiveObject head(String key) {
        return objects.containsKey(key) ? object(key) : null;
    }

    @Override
    public InputStream open(String key) throws IOException {
        Entry entry = objects.get(key);
        if (entry == null) throw new FileNotFoundException(key);
        opened.incrementAndGet();

        if (readDelayMs > 0) {
            try {
                Thread.sleep(readDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        return new ByteArrayInputStream(entry.data());
    }

    private ArchiveObject object(String key) {
        Entry entry = objects.get(key);
        return new ArchiveObject(key, entry.data().length, entry.lastModifiedUs());
    }
}

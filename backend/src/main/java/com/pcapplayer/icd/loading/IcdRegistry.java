package com.pcapplayer.icd.loading;

import com.pcapplayer.icd.model.IcdDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The ICDs the server knows, by id. Thread-safe. */
public final class IcdRegistry {

    private final Map<String, IcdDefinition> byId = new ConcurrentHashMap<>();

    /** A registry holding the Java ICDs listed in {@link BuiltInIcds}; a broken one stops startup. */
    public static IcdRegistry withBuiltIns() throws IcdLoadException {
        IcdRegistry registry = new IcdRegistry();
        for (Class<?> holder : BuiltInIcds.HOLDERS) registry.put(AnnotationIcdLoader.load(holder));
        return registry;
    }

    public void put(IcdDefinition icd) {
        byId.put(icd.id(), icd);
    }

    /** The ICD with this id, or null. */
    public IcdDefinition find(String id) {
        return id == null ? null : byId.get(id);
    }

    /** Sorted by id. */
    public List<IcdDefinition> all() {
        List<IcdDefinition> all = new ArrayList<>(byId.values());
        all.sort(Comparator.comparing(IcdDefinition::id));
        return all;
    }
}

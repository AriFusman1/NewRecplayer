package com.pcapplayer.recordManagement.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pcapplayer.icd.decode.DecodeStats;
import com.pcapplayer.icd.decode.ScriptDecoder;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.profile.TimeFixProfile;
import com.pcapplayer.icd.timefix.PatchPlan;
import com.pcapplayer.icd.timefix.PostPatchHook;
import com.pcapplayer.icd.timefix.TimeFixPlanner;
import com.pcapplayer.icd.timefix.TimeFixStats;
import com.pcapplayer.pcapProcessing.extract.ExtractionFilter;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.playback.model.TimeFix;
import com.pcapplayer.recordManagement.model.Category;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the time-fix plan of a record before it plays: decodes the whole script with the category's
 * ICD and plans every message the profile covers. Categories without an ICD or an active profile cost
 * nothing (no decoding).
 * <p>
 * Plans are cached (Caffeine, a few records), keyed by the capture, the record's filter, the ICD and
 * the profile, so replaying a record or a playlist again does not decode again. Any failure here only
 * disables time fixing for that replay: it is logged, and the record plays as recorded.
 */
final class TimeFixPlans {

    private static final Logger LOG = Logger.getLogger(TimeFixPlans.class.getName());
    private static final int MAX_CACHED_PLANS = 32;

    private record Key(String sha256, ExtractionFilter filter, IcdDefinition icd, TimeFixProfile profile) {
    }

    private final IcdService icds;
    private final TimeFixService profiles;
    private final Cache<Key, TimeFix> cache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHED_PLANS)
            .executor(Runnable::run)
            .build();

    TimeFixPlans(IcdService icds, TimeFixService profiles) {
        this.icds = icds;
        this.profiles = profiles;
    }

    /**
     * @param sha256 the capture's hash, with {@code filter} identifying the script
     * @return the plan, or null to play the record as recorded
     */
    TimeFix planFor(Category category, String sha256, ExtractionFilter filter, ReplayScript script) {
        TimeFixProfile profile = profiles.activeProfile(category);
        if (profile == null) return null;
        IcdDefinition icd = icds.find(profile.icdId());
        if (icd == null) return null;

        try {
            return cache.get(new Key(sha256, filter, icd, profile), key -> build(icd, profile, script, filter.startNanos()));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Time fixing disabled for a record of " + category.name() + ": planning failed", e);
            return null;
        }
    }

    private static TimeFix build(IcdDefinition icd, TimeFixProfile profile, ReplayScript script, long rangeStartNanos) {
        long started = System.nanoTime();
        TimeFixPlanner planner = new TimeFixPlanner(profile, PostPatchHook.NONE, new TimeFixStats());
        DecodeStats stats = ScriptDecoder.decode(script, rangeStartNanos, icd, planner);
        PatchPlan plan = planner.plan();
        LOG.info(() -> "Time-fix plan with ICD " + icd.id() + ": " + plan.messageCount() + " of " + stats.messages()
                + " decoded messages, problems " + stats.summary().problems() + ", "
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms");
        return new TimeFix(icd.id(), plan);
    }
}

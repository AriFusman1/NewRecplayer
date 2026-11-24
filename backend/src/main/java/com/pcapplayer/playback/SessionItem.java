package com.pcapplayer.playback;

import com.pcapplayer.playback.model.ScriptSource;
import com.pcapplayer.playback.model.Target;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One record to play within a session, aimed at a target. The pause after it and its status can
 * change from other threads while the session runs.
 */
final class SessionItem {

    final String itemId;
    final ScriptSource.RecordInfo info;
    final Target target;
    final AtomicLong sleepAfterMs;
    final AtomicReference<EItemStatus> status = new AtomicReference<>(EItemStatus.PENDING);

    SessionItem(String itemId, ScriptSource.RecordInfo info, Target target, long sleepAfterMs) {
        this.itemId = itemId;
        this.info = info;
        this.target = target;
        this.sleepAfterMs = new AtomicLong(sleepAfterMs);
    }
}

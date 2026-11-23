package com.pcapplayer.pcap.summary;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TimelineTest {

    private static final long MS = 1_000_000;

    @Test
    void shortCaptureKeepsOneMillisecondBuckets() {
        Timeline timeline = new Timeline(0);
        timeline.count(timeline.index(0));
        timeline.count(timeline.index(MS / 2));
        timeline.count(timeline.index(3 * MS));
        assertEquals(1000, timeline.binUs());
        assertArrayEquals(new long[]{2, 0, 0, 1}, timeline.counts());
    }

    @Test
    void widensAndMergesTotalsAndSeries() {
        Timeline timeline = new Timeline(1_000 * MS);
        Timeline.Series firstHalf = timeline.newSeries();
        Timeline.Series secondHalf = timeline.newSeries();
        long total = 0;
        // one packet every 10 ms for 10 s: forces several doublings past 256 buckets
        for (long offsetMs = 0; offsetMs < 10_000; offsetMs += 10) {
            int bucket = timeline.index(1_000 * MS + offsetMs * MS);
            timeline.count(bucket);
            total++;
            (offsetMs < 5_000 ? firstHalf : secondHalf).add(bucket);
        }
        long[] counts = timeline.counts();
        assertEquals(total, Arrays.stream(counts).sum());
        // 10 s / 256 buckets -> 64 ms buckets (1 ms doubled until it fits)
        assertEquals(64_000, timeline.binUs());
        assertEquals(500, Arrays.stream(firstHalf.counts()).sum());
        assertEquals(500, Arrays.stream(secondHalf.counts()).sum());
        assertEquals(0, firstHalf.from());
        assertEquals(5_000 / 64, secondHalf.from());
    }

    @Test
    void seriesGrowsLeftForOutOfOrderPackets() {
        Timeline timeline = new Timeline(0);
        Timeline.Series series = timeline.newSeries();
        series.add(timeline.index(5 * MS));
        series.add(timeline.index(2 * MS));
        assertEquals(2, series.from());
        assertArrayEquals(new int[]{1, 0, 0, 1}, series.counts());
    }

    @Test
    void timestampsBeforeTheBaseLandInTheFirstBucket() {
        Timeline timeline = new Timeline(10 * MS);
        assertEquals(0, timeline.index(5 * MS));
    }
}

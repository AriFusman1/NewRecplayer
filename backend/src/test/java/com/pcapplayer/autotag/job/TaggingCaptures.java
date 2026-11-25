package com.pcapplayer.autotag.job;

import com.pcapplayer.testSupport.CaptureFiles;
import com.pcapplayer.testSupport.Frames;
import com.pcapplayer.testSupport.SampleIcdMessages;
import com.pcapplayer.testSupport.SampleIcdMessages.TargetValues;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Synthetic captures for tagging tests: one TCP connection 10.0.0.1:40000 → 10.0.0.2:5000 carrying ICD messages. */
final class TaggingCaptures {

    static final int SERVER_PORT = 5000;
    static final long START_NANOS = 1_700_000_000_000_000_000L;
    static final long STEP_NANOS = 1_000_000; // 1 ms between packets

    private TaggingCaptures() {
    }

    /** SYN, SYN+ACK, ACK, then one segment per message, 1 ms apart. */
    static byte[] tcpCapture(Path scratch, byte[]... messages) throws IOException {
        List<CaptureFiles.Frame> frames = new ArrayList<>();
        long ts = START_NANOS;
        long clientSeq = 1000;
        frames.add(frame(ts, "10.0.0.1", "10.0.0.2", Frames.tcp(40000, SERVER_PORT, clientSeq, 0, Frames.SYN, new byte[0])));
        frames.add(frame(ts += STEP_NANOS, "10.0.0.2", "10.0.0.1", Frames.tcp(SERVER_PORT, 40000, 9000, clientSeq + 1, Frames.SYN | Frames.ACK, new byte[0])));
        clientSeq++;
        frames.add(frame(ts += STEP_NANOS, "10.0.0.1", "10.0.0.2", Frames.tcp(40000, SERVER_PORT, clientSeq, 9001, Frames.ACK, new byte[0])));

        for (byte[] message : messages) {
            frames.add(frame(ts += STEP_NANOS, "10.0.0.1", "10.0.0.2",
                    Frames.tcp(40000, SERVER_PORT, clientSeq, 9001, Frames.PSH | Frames.ACK, message)));
            clientSeq += message.length;
        }
        Path file = Files.createTempFile(scratch, "capture", ".pcap");
        CaptureFiles.writePcap(file, CaptureFiles.LINKTYPE_ETHERNET, frames);
        byte[] bytes = Files.readAllBytes(file);
        Files.delete(file);
        return bytes;
    }

    /** Capture time of the n-th message (0-based) in {@link #tcpCapture}. */
    static long messageNanos(int n) {
        return START_NANOS + (3 + n) * STEP_NANOS;
    }

    private static CaptureFiles.Frame frame(long ts, String src, String dst, byte[] tcp) {
        return new CaptureFiles.Frame(ts, Frames.ethernet(Frames.ipv4(src, dst, Frames.PROTO_TCP, tcp)));
    }

    /** {@code count} Track messages with one target of this kind; {@code seed} makes the content unique. */
    static byte[][] tracks(int count, byte kind, int seed) {
        byte[][] messages = new byte[count][];

        for (int i = 0; i < count; i++) {
            messages[i] = SampleIcdMessages.track(seed * 1000L + i, 0, 0, 0, 0, new TargetValues(seed, 1f, 2f, (short) 10, 0, kind));
        }
        return messages;
    }

    static byte[][] statuses(int count) {
        byte[][] messages = new byte[count][];
        for (int i = 0; i < count; i++) messages[i] = SampleIcdMessages.status(i, 0, 0);
        return messages;
    }

    static byte[][] concat(byte[][] first, byte[][] second) {
        byte[][] all = new byte[first.length + second.length][];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}

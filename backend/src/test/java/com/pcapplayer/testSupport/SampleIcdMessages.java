package com.pcapplayer.testSupport;

import com.pcapplayer.icd.loading.AnnotationIcdLoader;
import com.pcapplayer.icd.loading.IcdLoadException;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.versions.sample.SampleIcd;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Builds messages of the sample ICD byte by byte, independently of the decoder, so tests can check the
 * decoder against bytes written by hand.
 * <pre>
 * header (12, big endian): opcode u16, length u16 (whole message), sequence u32, sendTime u32
 * Status (1), 37 bytes:    status u8, flags u8, reserved 2, unitName 8, timestampMs i64 BE,
 *                          counter u32 LE, healthy u8
 * Track (2), 40 + 17·n:    gpsTime u64 LE, epochSeconds u32 BE, ltimeExact f64 LE, targetCount u8,
 *                          n × (id u16, lat f32, lon f32, alt i16, seenGps u32, kind i8) LE,
 *                          samples 3 × i16 LE, temperature i8
 * </pre>
 */
public final class SampleIcdMessages {

    public static final int HEADER_SIZE = 12;
    public static final int STATUS_SIZE = 37;
    public static final int TARGET_SIZE = 17;

    /** Offset of Status.timestampMs in the message. */
    public static final int STATUS_TIMESTAMP_OFFSET = 24;

    /** One target of a Track message. */
    public record TargetValues(int id, float lat, float lon, short alt, long seenGps, byte kind) {
    }

    private SampleIcdMessages() {
    }

    public static IcdDefinition icd() {
        try {
            return AnnotationIcdLoader.load(SampleIcd.class);
        } catch (IcdLoadException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] status(long sequence, long sendTime, long timestampMs) {
        ByteBuffer body = ByteBuffer.allocate(STATUS_SIZE - HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        body.put((byte) 2);                            // status = OPERATIONAL
        body.put((byte) 0b1_101_0011);                 // ready = 1, mode = 5, spare = 3
        body.put(new byte[2]);                         // reserved
        body.put(Arrays.copyOf("UNIT-A".getBytes(StandardCharsets.US_ASCII), 8));
        body.putLong(timestampMs);
        body.order(ByteOrder.LITTLE_ENDIAN).putInt(0x01020304); // counter, little endian
        body.put((byte) 1);                            // healthy
        return withHeader(1, sequence, sendTime, body.array(), STATUS_SIZE);
    }

    public static byte[] track(long sequence, long sendTime, long gpsTime, long epochSeconds, double ltimeExact,
                               TargetValues... targets) {
        ByteBuffer body = ByteBuffer.allocate(28 + TARGET_SIZE * targets.length).order(ByteOrder.LITTLE_ENDIAN);
        body.putLong(gpsTime);
        body.order(ByteOrder.BIG_ENDIAN).putInt((int) epochSeconds).order(ByteOrder.LITTLE_ENDIAN);
        body.putDouble(ltimeExact);
        body.put((byte) targets.length);

        for (TargetValues target : targets) {
            body.putShort((short) target.id()).putFloat(target.lat()).putFloat(target.lon()).putShort(target.alt());
            body.putInt((int) target.seenGps()).put(target.kind());
        }
        body.putShort((short) -1).putShort((short) 0).putShort((short) 300); // samples
        body.put((byte) -40);                                                // temperature
        return withHeader(2, sequence, sendTime, body.array(), HEADER_SIZE + body.capacity());
    }

    /** A message with the given header values and raw body; {@code length} goes in the length field as is. */
    public static byte[] withHeader(int opcode, long sequence, long sendTime, byte[] body, int length) {
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        header.putShort((short) opcode).putShort((short) length).putInt((int) sequence).putInt((int) sendTime);
        return concat(header.array(), body);
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}

package com.pcapplayer.icd.decode;

import com.pcapplayer.icd.model.MessageDef;
import com.pcapplayer.pcapProcessing.script.ScriptFlow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntToLongFunction;

/**
 * Application-level reassembly for one flow: cuts the flow's byte stream into ICD messages.
 * <p>
 * The flow's packets arrive in order (TCP retransmissions, reordering and IP fragments were already
 * handled by the extraction, so these are the bytes exactly as replayed). They are appended to a
 * buffer, remembering which packet every byte came from. Then, repeatedly:
 * <ol>
 *   <li>wait for a whole header, read the opcode and the length field;</li>
 *   <li>an impossible length means we are not at a message start: drop the rest of the current packet
 *       and try again at the next packet's first byte, where messages usually start
 *       ({@link EDecodeProblem#INVALID_LENGTH});</li>
 *   <li>otherwise wait until the whole message is buffered, however many packets that takes, and cut it
 *       out. A known opcode is decoded; an unknown one is skipped whole, which keeps the stream aligned
 *       ({@link EDecodeProblem#UNKNOWN_OPCODE}).</li>
 * </ol>
 * A capture gap or the end of the flow drops a partial message ({@link EDecodeProblem#TRUNCATED}).
 * Skipped bytes are never lost: playback sends every packet as recorded; they are just not patched.
 */
final class FlowFramer {

    private static final int INITIAL_BUFFER = 4096;

    private final MessageDecoder decoder;
    private final int flowId;
    private final ScriptFlow flow;
    private final DecodeStats stats;
    private final IntToLongFunction stepTsNanos;
    private final Consumer<DecodedMessage> out;

    /** Buffered bytes are {@code buffer[start, end)}. */
    private byte[] buffer = new byte[INITIAL_BUFFER];
    private int start;
    private int end;

    /** Where each buffered byte came from, in order; together exactly {@code end - start} bytes. */
    private final ArrayDeque<ByteSpan> origins = new ArrayDeque<>();

    /**
     * @param stepTsNanos capture time of a script step
     * @param out         receives every decoded message
     */
    FlowFramer(MessageDecoder decoder, int flowId, ScriptFlow flow, DecodeStats stats, IntToLongFunction stepTsNanos,
               Consumer<DecodedMessage> out) {
        this.decoder = decoder;
        this.flowId = flowId;
        this.flow = flow;
        this.stats = stats;
        this.stepTsNanos = stepTsNanos;
        this.out = out;
    }

    /** The flow's next packet, script step {@code step}. */
    void add(int step, byte[] data) {
        if (data.length == 0) return;
        append(data);
        origins.addLast(new ByteSpan(step, 0, data.length));
        cutMessages();
    }

    /** The stream breaks here (capture gap, end of flow): a partial message cannot be completed. */
    void reset(String reason) {
        int partial = end - start;
        if (partial > 0) stats.problem(EDecodeProblem.TRUNCATED, partial, where(), reason + " inside a message");
        start = 0;
        end = 0;
        origins.clear();
    }

    private void cutMessages() {
        int headerSize = decoder.headerSize();

        while (end - start >= headerSize) {
            long size = decoder.messageSize(buffer, start);
            long maxSize = decoder.icd().header().maxMessageBytes();

            if (size < headerSize || size > maxSize) {
                int dropped = dropRestOfFirstPacket();
                stats.problem(EDecodeProblem.INVALID_LENGTH, dropped, where(),
                        "length gives " + size + " bytes (allowed " + headerSize + ".." + maxSize + "); resync at the next packet");
                continue;
            }
            if (end - start < size) return; // wait for more packets

            long opcode = decoder.opcode(buffer, start);
            List<ByteSpan> pieces = takeOrigins((int) size);
            byte[] message = Arrays.copyOfRange(buffer, start, start + (int) size);
            start += (int) size;
            emit(opcode, message, pieces);
        }
        compact();
    }

    private void emit(long opcode, byte[] message, List<ByteSpan> pieces) {
        MessageDef definition = decoder.icd().message(opcode);

        if (definition == null) {
            stats.problem(EDecodeProblem.UNKNOWN_OPCODE, message.length, where(), "opcode " + opcode + " is not in the ICD");
            return;
        }

        try {
            MessageDecoder.DecodedFields fields = decoder.decode(definition, message);
            int firstStep = pieces.get(0).step();
            int lastStep = pieces.get(pieces.size() - 1).step();
            stats.message();
            out.accept(new DecodedMessage(opcode, definition.name(), flowId, flow, stepTsNanos.applyAsLong(firstStep),
                    firstStep, lastStep, message.length, fields.trailingBytes(), fields.values(), fields.slots(), pieces));
        } catch (MessageDecodeException | RuntimeException e) {
            stats.problem(EDecodeProblem.PARSE_ERROR, message.length, where(), definition.name() + ": " + e.getMessage());
        }
    }

    /** Drops the buffered bytes of the oldest packet, so the next attempt starts at a packet boundary. */
    private int dropRestOfFirstPacket() {
        ByteSpan first = origins.removeFirst();
        start += first.length();
        return first.length();
    }

    /** Removes the origins of the next {@code size} buffered bytes and returns them as the message's pieces. */
    private List<ByteSpan> takeOrigins(int size) {
        List<ByteSpan> pieces = new ArrayList<>();
        int remaining = size;

        while (remaining > 0) {
            ByteSpan first = origins.removeFirst();

            if (first.length() <= remaining) {
                pieces.add(first);
                remaining -= first.length();
            } else {
                // the message ends inside this packet: the rest of the packet stays buffered
                pieces.add(new ByteSpan(first.step(), first.offset(), remaining));
                origins.addFirst(new ByteSpan(first.step(), first.offset() + remaining, first.length() - remaining));
                remaining = 0;
            }
        }
        return pieces;
    }

    private void append(byte[] data) {
        if (end + data.length > buffer.length) {
            compact();
            if (end + data.length > buffer.length) buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, end + data.length));
        }
        System.arraycopy(data, 0, buffer, end, data.length);
        end += data.length;
    }

    /** Moves the buffered bytes to the front of the buffer. */
    private void compact() {
        if (start == 0) return;
        System.arraycopy(buffer, start, buffer, 0, end - start);
        end -= start;
        start = 0;
    }

    private String where() {
        return flow != null ? flow.describe() : "flow " + flowId;
    }
}

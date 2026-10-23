package com.pcapplayer.capture;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Opens a capture file, detecting pcap vs. pcapng from its magic number. */
public final class CaptureReaders {

    private static final int BUFFER = 1 << 20;

    private CaptureReaders() {
    }

    public static CaptureReader open(Path file) throws IOException {
        return open(Files.newInputStream(file));
    }

    public static CaptureReader open(InputStream raw) throws IOException {
        StreamInput in = new StreamInput(new BufferedInputStream(raw, BUFFER));
        try {
            byte[] magic = in.readBytesOrNull(4);
            if (magic == null) {
                throw new InvalidCaptureException("Empty file");
            }
            in.littleEndian(false);
            if (in.u32(magic, 0) == PcapNgReader.SHB) {
                return new PcapNgReader(in, magic);
            }
            return new ClassicPcapReader(in, magic);
        } catch (InvalidCaptureException e) {
            in.close();
            throw e;
        } catch (IOException e) {
            in.close();
            throw new InvalidCaptureException("Not a pcap/pcapng file");
        }
    }
}

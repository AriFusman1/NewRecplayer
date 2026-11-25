package com.pcapplayer.uploadHandling;

import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.pcapProcessing.extract.ExtractionSettings;
import com.pcapplayer.pcapProcessing.summary.CaptureScanner;
import com.pcapplayer.testSupport.CaptureFiles;
import com.pcapplayer.testSupport.Frames;
import com.pcapplayer.testSupport.Gzip;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The regular upload path accepts gzipped captures through {@code PcapInputOpener}. */
class UploadServiceGzipTest {

    @TempDir
    Path dir;

    @Test
    void gzippedUploadIsStoredDecompressedUnderItsPlainName() throws Exception {
        CaptureFiles.assumeLibpcap(dir);
        byte[] frame = Frames.ethernet(Frames.ipv4("10.0.0.1", "10.0.0.2", Frames.PROTO_UDP, Frames.udp(4000, 5000, new byte[]{1, 2, 3})));
        Path pcap = CaptureFiles.writePcap(dir.resolve("plain.pcap"), CaptureFiles.LINKTYPE_ETHERNET,
                List.of(new CaptureFiles.Frame(1_000_000_000L, frame)));
        UploadService uploads = new UploadService(dir.resolve("uploads"), new CaptureScanner(ExtractionSettings.defaults()), 60_000);
        Path received = Gzip.gzipFile(pcap, uploads.newTempPath());

        UploadService.Upload upload = uploads.register(received, "capture.pcap.gz", "tester");

        assertEquals("capture.pcap", upload.originalName());
        assertEquals(1, upload.summary().udpPackets());
        assertArrayEquals(Files.readAllBytes(pcap), Files.readAllBytes(upload.path()));
        assertFalse(Files.exists(received), "the compressed temp file is deleted");
    }

    @Test
    void gzippedNonPcapIsRejected() throws Exception {
        UploadService uploads = new UploadService(dir.resolve("uploads"), new CaptureScanner(ExtractionSettings.defaults()), 60_000);
        Path received = Files.write(uploads.newTempPath(), Gzip.gzip("text".getBytes(StandardCharsets.US_ASCII)));

        ApiException error = assertThrows(ApiException.class, () -> uploads.register(received, "x.gz", "tester"));

        assertEquals("INVALID_FILE", error.code());
        assertFalse(Files.exists(received));
    }
}

package com.pcapplayer.autotag.job;

import com.pcapplayer.pcapProcessing.extract.ExtractionSettings;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.testSupport.CaptureFiles;
import com.pcapplayer.testSupport.Frames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServicePortsTest {

    @TempDir
    Path dir;

    private static CaptureFiles.Frame frame(long ts, String src, String dst, int protocol, byte[] l4) {
        return new CaptureFiles.Frame(ts, Frames.ethernet(Frames.ipv4(src, dst, protocol, l4)));
    }

    @Test
    void tcpServerPortComesFromTheSyn() throws Exception {
        CaptureFiles.assumeLibpcap(dir);
        Path pcap = Files.write(dir.resolve("tcp.pcap"), TaggingCaptures.tcpCapture(dir, new byte[]{1, 2, 3}));

        assertEquals(Set.of(TaggingCaptures.SERVER_PORT), ServicePorts.find(pcap, EConnectionMode.TCP_SERVER, ExtractionSettings.defaults()));
        assertEquals(Set.of(TaggingCaptures.SERVER_PORT), ServicePorts.find(pcap, EConnectionMode.TCP_CLIENT, ExtractionSettings.defaults()));
        assertEquals(Set.of(), ServicePorts.find(pcap, EConnectionMode.UDP_SERVER, ExtractionSettings.defaults()), "no UDP in it");
    }

    @Test
    void withoutASynTheLowerPortIsTheServer() throws Exception {
        CaptureFiles.assumeLibpcap(dir);
        Path pcap = CaptureFiles.writePcap(dir.resolve("udp.pcap"), CaptureFiles.LINKTYPE_ETHERNET, List.of(
                frame(1_000, "10.0.0.1", "10.0.0.2", Frames.PROTO_UDP, Frames.udp(51000, 7000, new byte[]{1})),
                frame(2_000, "10.0.0.2", "10.0.0.1", Frames.PROTO_UDP, Frames.udp(7000, 51000, new byte[]{2})),
                frame(3_000, "10.0.0.3", "10.0.0.2", Frames.PROTO_UDP, Frames.udp(9000, 52000, new byte[]{3}))));

        assertEquals(Set.of(7000, 9000), ServicePorts.find(pcap, EConnectionMode.UDP_SERVER, ExtractionSettings.defaults()));
    }
}

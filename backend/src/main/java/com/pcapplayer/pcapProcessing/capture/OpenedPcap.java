package com.pcapplayer.pcapProcessing.capture;

import java.nio.file.Path;

/**
 * The result of {@link PcapInputOpener}: where the plain capture now is and what it was.
 *
 * @param path    the decompressed capture on local disk; null when {@code kind} is NOT_A_PCAP
 * @param name    the original name, without a trailing ".gz" when the content was gzipped
 * @param gzipped true if the input was gzip-compressed (detected by its bytes, not its name)
 * @param size    bytes of the decompressed capture (0 for NOT_A_PCAP)
 * @param sha256  hex SHA-256 of the decompressed capture; null for NOT_A_PCAP
 */
public record OpenedPcap(Path path, String name, boolean gzipped, EInputKind kind, long size, String sha256) {

    public boolean isPcap() {
        return kind != EInputKind.NOT_A_PCAP;
    }
}

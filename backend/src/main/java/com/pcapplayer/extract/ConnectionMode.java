package com.pcapplayer.extract;

/** How the original service on our server talked to the source (defined by a record's category). */
public enum ConnectionMode {
    /** Service was a TCP server; the replayer connects to it. Source data: destination port ∈ ports. */
    TCP_SERVER(true, true),
    /** Service received UDP; the replayer sends datagrams. Source data: destination port ∈ ports. */
    UDP_SERVER(false, true),
    /** Service was a TCP client of the source; the replayer listens. Source data: source port ∈ ports. */
    TCP_CLIENT(true, false);

    private final boolean tcp;
    private final boolean filterOnDestination;

    ConnectionMode(boolean tcp, boolean filterOnDestination) {
        this.tcp = tcp;
        this.filterOnDestination = filterOnDestination;
    }

    public boolean tcp() {
        return tcp;
    }

    /** True if the port/IP filter applies to the packet's destination, false for its source. */
    public boolean filterOnDestination() {
        return filterOnDestination;
    }

    public String protocol() {
        return tcp ? "TCP" : "UDP";
    }
}

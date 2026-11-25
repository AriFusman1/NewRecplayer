package com.pcapplayer.pcapProcessing.script;

import com.pcapplayer.pcapProcessing.decode.Ipv4Packet;

/**
 * One recorded flow in a script, from the source to the service. For TCP every connection is its own
 * flow (a reconnect on the same ports starts a new one); for UDP every 4-tuple is one flow.
 *
 * @param id index in {@link ReplayScript#flows()}, referenced by {@link ScriptStep.Send#flowId()}
 */
public record ScriptFlow(int id, boolean tcp, int srcIp, int srcPort, int dstIp, int dstPort) {

    /** "TCP 10.0.0.1:5000 → 10.0.0.2:6000". */
    public String describe() {
        return (tcp ? "TCP " : "UDP ") + Ipv4Packet.ipToString(srcIp) + ":" + srcPort + " → "
                + Ipv4Packet.ipToString(dstIp) + ":" + dstPort;
    }
}

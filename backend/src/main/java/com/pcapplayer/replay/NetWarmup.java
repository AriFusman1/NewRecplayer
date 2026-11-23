package com.pcapplayer.replay;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Exercises the JDK's UDP/TCP and timing code paths once over loopback (to our own sockets only), so
 * class loading and JIT don't delay the first message of the first replay.
 */
public final class NetWarmup {

    private NetWarmup() {
    }

    static void run() {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket receiver = new DatagramSocket(new InetSocketAddress(loopback, 0)); DatagramSocket sender = new DatagramSocket()) {
            byte[] buffer = new byte[64];
            for (int i = 0; i < 200; i++) {
                sender.send(new DatagramPacket(buffer, buffer.length, receiver.getLocalSocketAddress()));
                receiver.receive(new DatagramPacket(new byte[64], 64));
            }
        } catch (Exception ignored) {
            // warm-up is best effort
        }
        try (ServerSocket server = new ServerSocket(0, 1, loopback); Socket client = new Socket()) {
            client.setTcpNoDelay(true);
            client.connect(server.getLocalSocketAddress(), 1000);
            try (Socket accepted = server.accept(); OutputStream out = client.getOutputStream(); InputStream in = accepted.getInputStream()) {
                byte[] buffer = new byte[64];
                for (int i = 0; i < 200; i++) {
                    out.write(buffer);
                    out.flush();
                    in.readNBytes(64);
                }
            }
        } catch (Exception ignored) {
            // warm-up is best effort
        }
        Control control = new Control();
        for (int i = 0; i < 2000; i++) PreciseClock.sleepUntil(System.nanoTime() + 10_000, control);
        // a few real parks so the clock learns this host's wake-up latency before the first replay
        for (int i = 0; i < 40; i++) PreciseClock.sleepUntil(System.nanoTime() + 8_000_000, control);
    }
}

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
final class NetWarmup {

    private NetWarmup() {
    }

    static void run() {
        InetAddress lo = InetAddress.getLoopbackAddress();
        try (DatagramSocket rx = new DatagramSocket(new InetSocketAddress(lo, 0)); DatagramSocket tx = new DatagramSocket()) {
            byte[] b = new byte[64];
            for (int i = 0; i < 200; i++) {
                tx.send(new DatagramPacket(b, b.length, rx.getLocalSocketAddress()));
                rx.receive(new DatagramPacket(new byte[64], 64));
            }
        } catch (Exception ignored) {
            // warm-up is best effort
        }
        try (ServerSocket server = new ServerSocket(0, 1, lo); Socket c = new Socket()) {
            c.setTcpNoDelay(true);
            c.connect(server.getLocalSocketAddress(), 1000);
            try (Socket s = server.accept(); OutputStream out = c.getOutputStream(); InputStream in = s.getInputStream()) {
                byte[] b = new byte[64];
                for (int i = 0; i < 200; i++) {
                    out.write(b);
                    out.flush();
                    in.readNBytes(64);
                }
            }
        } catch (Exception ignored) {
            // warm-up is best effort
        }
        Control c = new Control();
        for (int i = 0; i < 2000; i++) PreciseClock.sleepUntil(System.nanoTime() + 10_000, c);
        // a few real parks so the clock learns this host's wake-up latency before the first replay
        for (int i = 0; i < 40; i++) PreciseClock.sleepUntil(System.nanoTime() + 8_000_000, c);
    }
}

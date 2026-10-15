package io.github.hectorvent.floci.testutil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

/** Helpers for choosing ports in tests that bind their own servers. */
public final class FreePorts {

    private FreePorts() {
    }

    /** Returns a port selected by binding on any local interface. */
    public static int anyInterfacePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Returns {@code count} distinct ports selected by binding on any local interface.
     * Every socket stays open until all ports are chosen, so the kernel cannot hand the same port
     * out twice. Calling {@link #anyInterfacePort()} in a row can, and a Vert.x instance silently
     * shares a port between its servers instead of failing the second bind.
     */
    public static int[] anyInterfacePorts(int count) throws IOException {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            int[] ports = new int[count];
            for (int i = 0; i < count; i++) {
                ServerSocket socket = new ServerSocket(0);
                sockets.add(socket);
                ports[i] = socket.getLocalPort();
            }
            return ports;
        } finally {
            for (ServerSocket socket : sockets) {
                socket.close();
            }
        }
    }

    /**
     * Returns a port selected by binding to IPv4 loopback, for host-side Docker bindings.
     * Docker Desktop needs this host port named explicitly: a daemon-picked host port is allocated
     * on the host side only, where the daemon that pushes and pulls cannot reach it.
     */
    public static int loopbackIpv4Port() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
}

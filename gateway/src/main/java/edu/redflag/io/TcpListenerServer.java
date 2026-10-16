package edu.redflag.io;

import edu.redflag.model.RawPacket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated TCP telemetry listener server for ingestion from network-attached IoT edge devices
 * (such as ESP32 nodes running Project Redflag firmware).
 * <p>
 * Listens on a configurable TCP port (default: 9876, configurable via the {@code rf.tcp.port} system property).
 * Incoming connections are accepted and handed off to dedicated {@link TcpClientHandler} worker threads.
 * Telemetry frames are enqueued into the shared {@link BlockingQueue} with back-pressure handling.
 * </p>
 */
public final class TcpListenerServer implements Runnable, AutoCloseable {

    private static volatile TcpListenerServer INSTANCE;

    private final int port;
    private final BlockingQueue<RawPacket> ingressQueue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CopyOnWriteArrayList<TcpClientHandler> activeHandlers = new CopyOnWriteArrayList<>();

    private volatile ServerSocket serverSocket;

    /**
     * Returns the globally active {@link TcpListenerServer} instance, or {@code null} if none is running.
     *
     * @return active server instance or null
     */
    public static TcpListenerServer getInstance() {
        return INSTANCE;
    }

    /**
     * Constructs a TCP listener server with the port retrieved from system property
     * {@code rf.tcp.port} (defaulting to 9876) and the given ingestion queue.
     *
     * @param ingressQueue shared queue into which raw telemetry packets are placed
     */
    public TcpListenerServer(final BlockingQueue<RawPacket> ingressQueue) {
        this(Integer.parseInt(System.getProperty("rf.tcp.port", "9876")), ingressQueue);
    }

    /**
     * Constructs a TCP listener server with an explicit port and the given ingestion queue.
     *
     * @param port listening port for incoming TCP connections
     * @param ingressQueue shared queue into which raw telemetry packets are placed
     */
    public TcpListenerServer(final int port, final BlockingQueue<RawPacket> ingressQueue) {
        this.port = port;
        this.ingressQueue = Objects.requireNonNull(ingressQueue, "Ingress queue must not be null");
        INSTANCE = this;
    }

    /**
     * Returns the port on which this server listens.
     *
     * @return port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Checks whether the TCP listener server is actively accepting connections.
     *
     * @return true if running and bound, false otherwise
     */
    public boolean isRunning() {
        final var s = serverSocket;
        return running.get() && s != null && s.isBound() && !s.isClosed();
    }

    /**
     * Returns a snapshot of node IDs for all connected clients that have emitted at least one valid packet.
     *
     * @return immutable list of active node IDs
     */
    public List<String> getConnectedNodes() {
        return activeHandlers.stream()
            .filter(TcpClientHandler::hasReceivedPacket)
            .map(TcpClientHandler::getNodeId)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
    }

    /**
     * Removes an active client handler from the server tracking registry.
     *
     * @param handler the client handler to remove
     */
    void removeHandler(final TcpClientHandler handler) {
        activeHandlers.remove(handler);
    }

    @Override
    public void run() {
        try (var s = new ServerSocket()) {
            this.serverSocket = s;
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            System.out.println("[TCP] Listening on port " + port);

            while (running.get()) {
                try {
                    final Socket clientSocket = s.accept();
                    final String clientIp = clientSocket.getInetAddress().getHostAddress();
                    System.out.println("[TCP] Client connected from " + clientIp);

                    final var handler = new TcpClientHandler(clientSocket, ingressQueue, this);
                    activeHandlers.add(handler);

                    final var clientThread = new Thread(
                        handler,
                        "tcp-client-" + clientIp + ":" + clientSocket.getPort()
                    );
                    clientThread.setDaemon(true);
                    clientThread.start();
                } catch (final SocketException se) {
                    if (!running.get()) {
                        break;
                    }
                    System.err.println("[TCP] Server socket exception: " + se.getMessage());
                } catch (final IOException ioe) {
                    if (!running.get()) {
                        break;
                    }
                    System.err.println("[TCP] Client accept failure: " + ioe.getMessage());
                }
            }
        } catch (final IOException e) {
            if (running.get()) {
                System.err.println("[TCP] Failed to bind or listen on port " + port + ": " + e.getMessage());
            }
        } finally {
            running.set(false);
            System.out.println("[TCP] Server stopped on port " + port);
        }
    }

    /**
     * Shuts down the TCP listener, closes the server socket, and terminates all active client connections.
     */
    public void shutdown() {
        running.set(false);
        final var s = serverSocket;
        if (s != null && !s.isClosed()) {
            try {
                s.close();
            } catch (final IOException e) {
                System.err.println("[TCP] Error closing server socket: " + e.getMessage());
            }
        }
        for (final var handler : activeHandlers) {
            handler.shutdown();
        }
        activeHandlers.clear();
    }

    @Override
    public void close() {
        shutdown();
    }
}

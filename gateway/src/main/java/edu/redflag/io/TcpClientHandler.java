package edu.redflag.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.redflag.core.DeviceRegistry;
import edu.redflag.model.RawPacket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dedicated per-client worker runnable servicing an active TCP telemetry connection.
 * <p>
 * Ingests line-delimited JSON telemetry streams from IoT devices (such as ESP32 nodes),
 * inspects the first packet to identify and register the remote {@code nodeId}, and forwards
 * packets into the gateway pipeline with non-blocking drop-oldest back-pressure.
 * </p>
 */
public final class TcpClientHandler implements Runnable, AutoCloseable {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final Socket socket;
    private final BlockingQueue<RawPacket> queue;
    private final TcpListenerServer server;
    private final String sourceIp;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong packetsReceived = new AtomicLong(0L);
    private final AtomicLong droppedFrames = new AtomicLong(0L);

    private volatile String nodeId = null;

    /**
     * Constructs a client handler bound to the specified socket and raw packet queue.
     *
     * @param socket active client connection socket
     * @param queue ingestion queue for parsed raw frames
     */
    public TcpClientHandler(final Socket socket, final BlockingQueue<RawPacket> queue) {
        this(socket, queue, null);
    }

    /**
     * Constructs a client handler bound to the specified socket, raw packet queue, and parent listener.
     *
     * @param socket active client connection socket
     * @param queue ingestion queue for parsed raw frames
     * @param server parent listener server tracking active sessions
     */
    public TcpClientHandler(final Socket socket, final BlockingQueue<RawPacket> queue, final TcpListenerServer server) {
        this.socket = Objects.requireNonNull(socket, "Socket must not be null");
        this.queue = Objects.requireNonNull(queue, "Queue must not be null");
        this.server = server;
        this.sourceIp = (socket.getInetAddress() != null)
            ? socket.getInetAddress().getHostAddress()
            : "unknown";
    }

    /**
     * Returns the remote IP address of the connected client.
     *
     * @return IPv4 or IPv6 address string
     */
    public String getSourceIp() {
        return sourceIp;
    }

    /**
     * Returns the cached node identifier parsed from telemetry frames.
     *
     * @return node ID or {@code null} if no valid frame has been processed yet
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * Checks if this client has emitted at least one valid packet and resolved its node ID.
     *
     * @return true if packets have been received and node ID is resolved
     */
    public boolean hasReceivedPacket() {
        return packetsReceived.get() > 0 && nodeId != null;
    }

    /**
     * Returns the total count of valid frames ingested from this TCP connection.
     *
     * @return number of frames received
     */
    public long getPacketsReceived() {
        return packetsReceived.get();
    }

    /**
     * Returns the number of frames dropped due to back-pressure queue overflow.
     *
     * @return number of dropped frames
     */
    public long getDroppedFrames() {
        return droppedFrames.get();
    }

    /**
     * Transmits a command string back to the edge device over full-duplex TCP stream (e.g. "BLOCK" or "SAFE").
     *
     * @param command ASCII/UTF-8 command string to transmit
     */
    public void writeCommand(final String command) {
        if (!running.get() || socket.isClosed()) {
            return;
        }
        try {
            final OutputStream os = socket.getOutputStream();
            final byte[] payload = (command + "\n").getBytes(StandardCharsets.UTF_8);
            os.write(payload);
            os.flush();
        } catch (final IOException e) {
            System.err.println("[TCP] Failed to write command to " + (nodeId != null ? nodeId : sourceIp) + ": " + e.getMessage());
        }
    }

    @Override
    public void run() {
        try (final var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                // If nodeId has not yet been resolved, extract and register it
                if (nodeId == null) {
                    final String parsedNode = parseNodeId(line);
                    if (parsedNode != null && !parsedNode.isBlank()) {
                        this.nodeId = parsedNode;
                        DeviceRegistry.registerTcpNode(parsedNode);
                        System.out.println("[TCP] Node " + parsedNode + " registered from " + sourceIp);
                    }
                }

                final var raw = new RawPacket(line, System.nanoTime());
                if (!queue.offer(raw)) {
                    // Back-pressure: drop oldest frame to preserve line-rate realtime detection
                    queue.poll();
                    queue.offer(raw);
                    droppedFrames.incrementAndGet();
                }
                packetsReceived.incrementAndGet();
            }
        } catch (final IOException e) {
            if (running.get()) {
                System.err.println("[TCP] Connection error from " + (nodeId != null ? nodeId : sourceIp) + ": " + e.getMessage());
            }
        } finally {
            final String id = nodeId;
            if (id != null) {
                DeviceRegistry.unregisterTcpNode(id);
                System.out.println("[TCP] Node " + id + " disconnected (" + sourceIp + ")");
            } else {
                System.out.println("[TCP] Client disconnected (" + sourceIp + ")");
            }

            if (server != null) {
                server.removeHandler(this);
            }
            shutdown();
        }
    }

    /**
     * Extracts the node identifier from a telemetry JSON line.
     *
     * @param line raw telemetry string
     * @return node ID string or null if unparseable
     */
    private String parseNodeId(final String line) {
        try {
            final JsonNode root = JSON_MAPPER.readTree(line);
            if (root != null && root.isObject()) {
                if (root.hasNonNull("node")) {
                    return root.path("node").asText();
                }
                if (root.hasNonNull("node_id")) {
                    return root.path("node_id").asText();
                }
            }
        } catch (final Exception e) {
            // Fast fallback: scan for "node":"<id>" via simple substring search
            final int nodeIdx = line.indexOf("\"node\"");
            if (nodeIdx != -1) {
                final int colonIdx = line.indexOf(':', nodeIdx);
                final int firstQuote = line.indexOf('"', colonIdx);
                final int secondQuote = (firstQuote != -1) ? line.indexOf('"', firstQuote + 1) : -1;
                if (firstQuote != -1 && secondQuote != -1) {
                    return line.substring(firstQuote + 1, secondQuote);
                }
            }
            System.err.println("[TCP] Failed to extract node identifier from payload: " + line + " (" + e.getMessage() + ")");
        }
        return null;
    }

    /**
     * Closes the client socket and terminates the handler thread.
     */
    public void shutdown() {
        running.set(false);
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (final IOException e) {
            System.err.println("[TCP] Error closing client socket (" + sourceIp + "): " + e.getMessage());
        }
    }

    @Override
    public void close() {
        shutdown();
    }
}

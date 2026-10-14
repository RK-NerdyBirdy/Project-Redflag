package edu.redflag.io;

import com.fazecast.jSerialComm.SerialPort;
import edu.redflag.model.RawPacket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dedicated serial ingestion runnable interfacing with physical Arduino over USB CDC.
 * Implements auto-resynchronization and exponential back-off reconnection.
 */
public final class SerialReaderRunnable implements Runnable, AutoCloseable {
    private final String portName;
    private final int baudRate;
    private final BlockingQueue<RawPacket> outQueue;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong framesRead = new AtomicLong(0L);
    private final AtomicLong droppedFrames = new AtomicLong(0L);
    private final AtomicLong reconnectCount = new AtomicLong(0L);

    private volatile SerialPort activePort;

    public SerialReaderRunnable(String portName, int baudRate, BlockingQueue<RawPacket> outQueue) {
        this.portName = portName;
        this.baudRate = baudRate;
        this.outQueue = outQueue;
    }

    @Override
    public void run() {
        long backoffMs = 250;

        while (running.get()) {
            try (SerialSession session = openSession()) {
                connected.set(true);
                backoffMs = 250;
                System.out.println("[SERIAL] Connected to port " + portName + " at " + baudRate + " baud.");

                String line;
                while (running.get() && (line = session.reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    RawPacket raw = new RawPacket(line, System.nanoTime());
                    if (!outQueue.offer(raw)) {
                        // Ingress back-pressure: drop oldest packet to prioritize latest line-rate telemetry
                        outQueue.poll();
                        outQueue.offer(raw);
                        droppedFrames.incrementAndGet();
                    }
                    framesRead.incrementAndGet();
                }
            } catch (IOException | RuntimeException e) {
                if (running.get()) {
                    System.err.println("[SERIAL] Connection lost on " + portName + ": " + e.getMessage());
                }
            } finally {
                connected.set(false);
            }

            if (!running.get()) break;

            reconnectCount.incrementAndGet();
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            backoffMs = Math.min(backoffMs * 2, 3000); // Max 3s back-off
        }
    }

    private SerialSession openSession() throws IOException {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        // Allow up to 10 seconds for Arduino bootloader startup and initial frame emission
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 10000, 0);

        if (!port.openPort()) {
            throw new IOException("Unable to open serial port: " + portName);
        }
        this.activePort = port;
        return new SerialSession(port);
    }

    /**
     * Transmits a command string back to Arduino over full-duplex serial link.
     */
    public void writeCommand(String cmd) {
        SerialPort port = activePort;
        if (port != null && port.isOpen()) {
            byte[] bytes = (cmd + "\n").getBytes(StandardCharsets.US_ASCII);
            port.writeBytes(bytes, bytes.length);
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public long getFramesRead() {
        return framesRead.get();
    }

    public long getDroppedFrames() {
        return droppedFrames.get();
    }

    @Override
    public void close() {
        running.set(false);
        SerialPort port = activePort;
        if (port != null && port.isOpen()) {
            port.closePort();
        }
    }

    private static final class SerialSession implements AutoCloseable {
        final SerialPort port;
        final BufferedReader reader;

        SerialSession(SerialPort port) {
            this.port = port;
            this.reader = new BufferedReader(new InputStreamReader(port.getInputStream(), StandardCharsets.US_ASCII));
        }

        @Override
        public void close() {
            if (port.isOpen()) {
                port.closePort();
            }
        }
    }
}

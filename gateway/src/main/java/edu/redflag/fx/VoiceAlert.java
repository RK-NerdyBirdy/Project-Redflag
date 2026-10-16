package edu.redflag.fx;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dedicated, non-blocking synthetic voice alert engine for Project Redflag.
 * Issues crisp spoken voice warnings on the edge gateway host whenever volumetric
 * flood DDoS attacks are detected on any IoT edge node.
 */
public final class VoiceAlert {
    private static final ExecutorService VOICE_POOL = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "redflag-voice-alert");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicLong lastVoiceMs = new AtomicLong(0L);
    private static final long COOLDOWN_MS = 6000L; // 6-second cooldown between voice warnings

    private VoiceAlert() {}

    /**
     * Speaks a high-priority attack alert for the specified node ID.
     *
     * @param nodeId originating IoT edge node identifier (e.g. "UNO-01", "ESP32-01")
     */
    public static void speakAttack(final String nodeId) {
        final long now = System.currentTimeMillis();
        final long last = lastVoiceMs.get();
        if (now - last < COOLDOWN_MS) {
            return;
        }
        if (!lastVoiceMs.compareAndSet(last, now)) {
            return;
        }

        final String cleanNode = (nodeId != null && !nodeId.isBlank()) ? nodeId.trim() : "Unknown";
        final String text = "Warning. Volumetric flood attack detected on node " + cleanNode + ". Initiating mitigation.";
        speak(text);
    }

    /**
     * Synthesizes and speaks text using native platform speech synthesis asynchronously.
     *
     * @param text phrase to speak
     */
    public static void speak(final String text) {
        VOICE_POOL.execute(() -> {
            try {
                final String os = System.getProperty("os.name", "").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("win")) {
                    final String escaped = text.replace("'", " ");
                    pb = new ProcessBuilder(
                        "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                        "(New-Object -ComObject SAPI.SpVoice).Speak('" + escaped + "')"
                    );
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("say", text);
                } else {
                    pb = new ProcessBuilder("spd-say", text);
                }
                pb.redirectErrorStream(true);
                final Process proc = pb.start();
                proc.waitFor(4, java.util.concurrent.TimeUnit.SECONDS);
            } catch (final Throwable ignored) {
                // Non-critical: ignore speech failures if audio hardware is absent
            }
        });
    }
}

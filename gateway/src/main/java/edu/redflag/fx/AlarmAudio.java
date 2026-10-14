package edu.redflag.fx;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated sound synthesizer for Project Redflag security alert notifications.
 * Generates an unmistakable, high-urgency tactical cyber defense alarm chime
 * via the Java Sound API (javax.sound.sampled) without requiring external audio files.
 */
public final class AlarmAudio {
    private static final ExecutorService AUDIO_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "redflag-alarm-audio");
        t.setDaemon(true);
        return t;
    });

    private static volatile long lastTriggerMs = 0;

    private AlarmAudio() {}

    /**
     * Plays a multi-frequency ascending tactical cyber-attack alarm chime:
     * Pulsing sequence (950Hz -> 1350Hz -> 1800Hz -> 1350Hz -> 1800Hz) with attack/decay envelope.
     */
    public static void playAttackAlert() {
        long now = System.currentTimeMillis();
        // Rate-limit consecutive sound bursts so high packet throughput doesn't clip/distort audio
        if (now - lastTriggerMs < 1200) {
            return;
        }
        lastTriggerMs = now;

        AUDIO_POOL.execute(() -> {
            try {
                int sampleRate = 44100;
                int[] tones = { 950, 1350, 1800, 1350, 1800 };
                int toneDurationMs = 70;
                int gapDurationMs = 25;

                int toneSamples = (int) (sampleRate * (toneDurationMs / 1000.0));
                int gapSamples = (int) (sampleRate * (gapDurationMs / 1000.0));
                int totalSamples = tones.length * (toneSamples + gapSamples);

                byte[] pcmData = new byte[totalSamples * 2]; // 16-bit mono
                int idx = 0;

                for (int freq : tones) {
                    for (int i = 0; i < toneSamples; i++) {
                        double angle = 2.0 * Math.PI * i * freq / sampleRate;

                        // Linear envelope smoothing to prevent speaker click/pop
                        double envelope = 1.0;
                        if (i < 250) {
                            envelope = (double) i / 250.0;
                        } else if (i > toneSamples - 250) {
                            envelope = (double) (toneSamples - i) / 250.0;
                        }

                        short sample = (short) (Math.sin(angle) * 28000.0 * envelope);
                        pcmData[idx++] = (byte) (sample & 0xFF);
                        pcmData[idx++] = (byte) ((sample >> 8) & 0xFF);
                    }

                    // Silence gap between pulses
                    for (int i = 0; i < gapSamples; i++) {
                        pcmData[idx++] = 0;
                        pcmData[idx++] = 0;
                    }
                }

                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

                if (AudioSystem.isLineSupported(info)) {
                    try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
                        line.open(format, pcmData.length);
                        line.start();
                        line.write(pcmData, 0, pcmData.length);
                        line.drain();
                    }
                } else {
                    java.awt.Toolkit.getDefaultToolkit().beep();
                }
            } catch (Exception e) {
                // Fallback to toolkit beep if hardware audio line is unavailable
                try {
                    java.awt.Toolkit.getDefaultToolkit().beep();
                } catch (Exception ignored) {}
            }
        });
    }
}

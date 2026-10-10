package edu.redflag.event;

import edu.redflag.model.TelemetryFrame;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe Observable hub distributing TelemetryFrames across JavaFX, JDBC Audit,
 * WebSocket, and MitigationController listeners.
 */
public final class TelemetryObservable {
    private final CopyOnWriteArrayList<TelemetryObserver> observers = new CopyOnWriteArrayList<>();

    public void subscribe(TelemetryObserver observer) {
        if (observer != null) {
            observers.addIfAbsent(observer);
        }
    }

    public void unsubscribe(TelemetryObserver observer) {
        observers.remove(observer);
    }

    public void publish(TelemetryFrame frame) {
        for (TelemetryObserver observer : observers) {
            try {
                observer.onFrame(frame);
            } catch (RuntimeException e) {
                System.err.println("Observer execution failed (" + observer.getClass().getSimpleName() + "): " + e.getMessage());
            }
        }
    }
}

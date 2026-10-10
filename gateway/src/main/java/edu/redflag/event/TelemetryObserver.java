package edu.redflag.event;

import edu.redflag.model.TelemetryFrame;

/**
 * Observer subscriber interface for receiving classified TelemetryFrame events.
 */
public interface TelemetryObserver {
    void onFrame(TelemetryFrame frame);
}

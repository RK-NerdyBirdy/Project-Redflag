---
name: javafx-dashboard
description: >-
  Strict architectural and UI guidelines for the Redflag JavaFX telemetry dashboard.
  Enforces zero-allocation render loops, AnimationTimer-based coalescing, strict 
  Red-Black CSS theming, and non-blocking overlays for attack states.
---

# JavaFX Dashboard Engineering Standards — Project Redflag

## 1. Architectural Constraints
- **Strict MVC:** `dashboard.fxml` contains layout ONLY. `DashboardController` contains view logic ONLY. 
- **Telemetry Ingestion:** `DashboardController` implements `TelemetryObserver`. It must NEVER block the calling network/lane thread.
- **Initialization Phase:** `RedflagApp` must launch a background `Task<Void>` for model loading and DB init. The primary Stage must instantly render a `#000000` splash screen with a red loading spinner.
- **Graceful Shutdown:** `stop()` must cascade shutdown signals: `NetworkReader` → `Pipeline` → `AuditLogger` → `Database`. 

## 2. High-Throughput Render Loop (CRITICAL)
Do **NOT** use `Platform.runLater` for high-frequency telemetry. It floods the JavaFX event queue and triggers GC pauses. Use an `AnimationTimer` connected to a lock-free ring buffer (or `ArrayBlockingQueue`).

**Standard Implementation:**
```java
// 1. Pre-allocated, bounded queue to prevent GC pressure (Drop oldest/newest on backpressure)
private final ArrayBlockingQueue<TelemetryFrame> inbox = new ArrayBlockingQueue<>(5000);

// 2. Network lane thread simply offers to the queue (non-blocking)
@Override
public void onFrame(TelemetryFrame f) {
    if (!inbox.offer(f)) {
        inbox.poll(); // Evict oldest
        inbox.offer(f);
    }
}

// 3. FX Thread pulls at exactly 60Hz via AnimationTimer
private final AnimationTimer renderLoop = new AnimationTimer() {
    @Override
    public void handle(long now) {
        drainQueue();
    }
};

private void drainQueue() {
    TelemetryFrame frame;
    int processed = 0;
    // Process up to 100 frames per 16.6ms frame limit to prevent scene graph lock
    while ((frame = inbox.poll()) != null && processed < 100) {
        updateCharts(frame);
        updateLabels(frame);
        processed++;
    }
}

```

## 3. Chart Optimization Rules

JavaFX charts will tank performance if not constrained. All charts MUST implement:

* `setAnimated(false)` and `setCreateSymbols(false)`.
* X-Axis `setAutoRanging(false)`. Manage the `lowerBound` and `upperBound` manually in the `drainQueue` loop to prevent expensive layout recalculations on every tick.
* **Hard Cap:** Series size MUST NOT exceed 200 data points. Call `series.getData().remove(0)` before adding the 201st point.

## 4. Red-Black Aesthetic (Dark Mode)

Strict adherence to the high-contrast Red-Black security palette. Do not use GitHub-style blues or greys.

| Element | Hex Code | FX CSS Implementation |
| --- | --- | --- |
| Window Root | `#050505` | `-fx-background-color: #050505;` |
| Panels/Cards | `#111111` | `-fx-background-color: #111111; -fx-border-color: #330000;` |
| Primary Text | `#E0E0E0` | `-fx-text-fill: #E0E0E0;` |
| Metric Values | `#FFFFFF` | `-fx-text-fill: #FFFFFF; -fx-font-weight: bold;` |
| Nominal State (Safe) | `#444444` | `-fx-text-fill: #444444;` *(No bright green)* |
| Attack State (Alert) | `#FF0000` | `-fx-text-fill: #FF0000; -fx-effect: dropshadow(gaussian, #FF0000, 15, 0.4, 0, 0);` |
| Chart Line (Series 1) | `#CC0000` | `-fx-stroke: #CC0000; -fx-stroke-width: 2px;` |
| Chart Area Fill | `#FF0000` | `-fx-fill: rgba(255, 0, 0, 0.15);` |
| Grid Lines | `#1A1A1A` | `-fx-stroke: #1A1A1A;` |

## 5. Non-Blocking Attack Alerts

**NEVER** use `Alert` or modal dialogs. Modals block the FX thread and require user interaction while an attack is actively occurring.

**Implementation:**

* Use a `StackPane` overlay mapped over the root layout.
* Bind its `visibleProperty()` to the threat state.
* When an attack crosses the threshold, slide in a notification banner at the top of the window using `TranslateTransition`.

```java
// Example non-blocking banner trigger
public void triggerAttackBanner(String node, double score) {
    alertLabel.setText(String.format("VOLUMETRIC FLOOD :: NODE [%s] :: SEV [%.2f]", node, score));
    alertPane.setVisible(true);
    // Add pulsing opacity transition to alertPane
}

```

## 6. Layout Structure (`dashboard.fxml`)

* **Root:** `StackPane` (allows alert banner overlay)
* **Base:** `BorderPane`
* **Top:** HBox (Title, Threat Status Label, Mitigation Toggle Button)
* **Center:** VBox (Contains the 3 Line/Area charts)
* **Bottom:** HBox (Throughput, Latency, Verdict Logs, ProgressBar)


* **Overlay:** HBox `alertPane` (Hidden by default, aligned to `Pos.TOP_CENTER`)



```


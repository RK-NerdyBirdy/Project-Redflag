// ============================================================================
// Project Redflag — ESP32 Edge Device Firmware (WiFi / TCP Streaming)
// Targets: ESP32 DevKit v1 / ESP32-WROOM-32 / ESP32-S3
// Emulates benign IoT sensor telemetry (TCP/CON) and volumetric Mirai UDP floods
// Streams line-delimited JSON telemetry frames directly to the Redflag Gateway over TCP.
// ============================================================================

#include <WiFi.h>
#include <WiFiClient.h>
#include <esp_timer.h>

// ============================================================================
// 1. User Configuration
// ============================================================================
#define WIFI_SSID     "Maneet"
#define WIFI_PASS     "Manu@123"
#define GATEWAY_HOST  "10.211.61.183"   // Java gateway IP address (Your PC WiFi IP)
#define GATEWAY_PORT  9876             // Java gateway TCP listening port
#define NODE_ID       "ESP32-01"

// Initial operating mode: 0 = BENIGN (Safe sensor), 1 = ATTACK (Mirai UDP flood)
#define ATTACK_MODE   0

// Optional 240x240 SPI TFT display support (e.g., ST7789 via TFT_eSPI)
#define HAS_TFT       0

#if HAS_TFT
#include <TFT_eSPI.h>
TFT_eSPI tft = TFT_eSPI();
#endif

// ============================================================================
// 2. Hardware Pin Definitions
// ============================================================================
// ESP32 standard boot button is on GPIO 0 (active LOW, internal pull-up)
#define BOOT_BTN_PIN  0

// Built-in status LED on ESP32 DevKit is on GPIO 2
#define LED_PIN       2

// ============================================================================
// 3. Operational Modes & Telemetry Constants
// ============================================================================
enum Mode : uint8_t {
  MODE_BENIGN = 0,
  MODE_ATTACK = 1
};

volatile Mode currentMode = (ATTACK_MODE == 1) ? MODE_ATTACK : MODE_BENIGN;

// Re-arm gap added to virtual clock when transitioning from ATTACK to BENIGN (6 seconds)
// This forces gateway rolling-window feature accumulators to reset cleanly.
const uint32_t REARM_GAP_US = 6000000;

// ============================================================================
// 4. Global State Variables
// ============================================================================
WiFiClient client;

// Sequence counter wraps at 2^31 (0x7FFFFFFF)
uint32_t seqNo = 0;

// Virtual microsecond timestamp accumulator
uint32_t virtualUs = 0;

// Emission timing tracking (microsecond scale)
int64_t nextEmitUs = 0;

// LED blink non-blocking timing
unsigned long lastLedToggleMs = 0;
bool ledState = false;

// WiFi reconnect state (exponential backoff)
unsigned long lastWifiRetryMs = 0;
unsigned long wifiBackoffMs = 1000;
const unsigned long MAX_WIFI_BACKOFF_MS = 30000;

// TCP reconnect state (exponential backoff)
unsigned long lastTcpRetryMs = 0;
unsigned long tcpBackoffMs = 500;
const unsigned long MAX_TCP_BACKOFF_MS = 10000;

// Feedback buffer from gateway ("BLOCK" / "SAFE")
char rxBuffer[32];
uint8_t rxIndex = 0;

// ============================================================================
// 5. Non-Blocking Button Debounce FSM (IDLE -> CANDIDATE -> LATCHED -> COOLDOWN)
// ============================================================================
enum ButtonFsmState : uint8_t {
  BTN_IDLE,
  BTN_CANDIDATE,
  BTN_LATCHED,
  BTN_COOLDOWN
};

ButtonFsmState btnState = BTN_IDLE;
unsigned long btnTimestamp = 0;
const unsigned long BTN_DEBOUNCE_MS = 30;
const unsigned long BTN_COOLDOWN_MS = 250;

// Forward declarations
void updateTftUI();
void toggleMode();

void pollButtonFSM() {
  bool isPressed = (digitalRead(BOOT_BTN_PIN) == LOW);
  unsigned long now = millis();

  switch (btnState) {
    case BTN_IDLE:
      if (isPressed) {
        btnState = BTN_CANDIDATE;
        btnTimestamp = now;
      }
      break;

    case BTN_CANDIDATE:
      if (!isPressed) {
        // Contact chatter / bounce; return to IDLE
        btnState = BTN_IDLE;
      } else if (now - btnTimestamp >= BTN_DEBOUNCE_MS) {
        // Valid press confirmed
        toggleMode();
        btnState = BTN_LATCHED;
        btnTimestamp = now;
      }
      break;

    case BTN_LATCHED:
      // Wait until the user releases the button
      if (!isPressed) {
        btnState = BTN_COOLDOWN;
        btnTimestamp = now;
      }
      break;

    case BTN_COOLDOWN:
      // Prevent rapid re-triggering immediately after release
      if (now - btnTimestamp >= BTN_COOLDOWN_MS) {
        btnState = BTN_IDLE;
      }
      break;
  }
}

void toggleMode() {
  if (currentMode == MODE_BENIGN) {
    currentMode = MODE_ATTACK;
    Serial.println(F("\n[MODE] >>> Switched to ATTACK mode (Volumetric Mirai UDP Flood) <<<"));
  } else {
    currentMode = MODE_BENIGN;
    // Step virtual time ahead by 6 seconds to cleanly reset the gateway window
    virtualUs += REARM_GAP_US;
    Serial.println(F("\n[MODE] >>> Switched to BENIGN mode (Normal IoT Sensor Traffic) <<<"));
  }

#if HAS_TFT
  updateTftUI();
#endif
}

// ============================================================================
// 6. LED Status Indicator (Slow blink = BENIGN, Fast blink = ATTACK)
// ============================================================================
void updateLedIndicator() {
  unsigned long now = millis();
  unsigned long interval = (currentMode == MODE_ATTACK) ? 80 : 800;

  if (now - lastLedToggleMs >= interval) {
    lastLedToggleMs = now;
    ledState = !ledState;
    digitalWrite(LED_PIN, ledState ? HIGH : LOW);
  }
}

// ============================================================================
// 7. Network Reconnection Handlers (WiFi & TCP)
// ============================================================================
void handleWiFiConnection() {
  if (WiFi.status() == WL_CONNECTED) {
    wifiBackoffMs = 1000;
    return;
  }

  unsigned long now = millis();
  if (now - lastWifiRetryMs >= wifiBackoffMs) {
    lastWifiRetryMs = now;
    Serial.print(F("[WIFI] Disconnected. Reconnecting to SSID '"));
    Serial.print(WIFI_SSID);
    Serial.println(F("'..."));

    WiFi.disconnect();
    WiFi.begin(WIFI_SSID, WIFI_PASS);

    wifiBackoffMs = (wifiBackoffMs * 2 > MAX_WIFI_BACKOFF_MS) ? MAX_WIFI_BACKOFF_MS : wifiBackoffMs * 2;
  }
}

void handleTcpConnection() {
  if (WiFi.status() != WL_CONNECTED) {
    return;
  }

  if (client.connected()) {
    tcpBackoffMs = 500;
    return;
  }

  unsigned long now = millis();
  if (now - lastTcpRetryMs >= tcpBackoffMs) {
    lastTcpRetryMs = now;
    Serial.print(F("[TCP] Connecting to Redflag Gateway at "));
    Serial.print(GATEWAY_HOST);
    Serial.print(':');
    Serial.println(GATEWAY_PORT);

    if (client.connect(GATEWAY_HOST, GATEWAY_PORT)) {
      client.setNoDelay(true); // Disable Nagle's algorithm for immediate packet dispatch
      Serial.println(F("[TCP] Successfully connected to Gateway!"));
      tcpBackoffMs = 500;
#if HAS_TFT
      updateTftUI();
#endif
    } else {
      Serial.println(F("[TCP] Connection failed. Backing off..."));
      tcpBackoffMs = (tcpBackoffMs * 2 > MAX_TCP_BACKOFF_MS) ? MAX_TCP_BACKOFF_MS : tcpBackoffMs * 2;
    }
  }
}

// ============================================================================
// 8. Gateway Feedback Ingestion ("BLOCK" / "SAFE")
// ============================================================================
void pollGatewayFeedback() {
  if (!client.connected()) return;

  while (client.available()) {
    char c = (char)client.read();
    if (c == '\n' || c == '\r') {
      if (rxIndex > 0) {
        rxBuffer[rxIndex] = '\0';
        rxIndex = 0;

        if (strcmp(rxBuffer, "BLOCK") == 0) {
          Serial.println(F("[GATEWAY FEEDBACK] VERDICT: MITIGATED (HTTP 403 / Hardware Blocked)"));
        } else if (strcmp(rxBuffer, "SAFE") == 0) {
          Serial.println(F("[GATEWAY FEEDBACK] VERDICT: LINK OK (HTTP 200 / Telemetry Allowed)"));
        }
      }
    } else if (rxIndex < sizeof(rxBuffer) - 1) {
      rxBuffer[rxIndex++] = c;
    }
  }
}

// ============================================================================
// 9. Telemetry Emission
// ============================================================================
void emitTelemetryFrame() {
  if (!client.connected()) {
    return;
  }

  const char* proto;
  const char* state;
  int bytes;
  int64_t deltaUs;

  if (currentMode == MODE_BENIGN) {
    // Benign mode: packet inter-arrival 5000us ± 2000us, bytes 64-512
    deltaUs = 5000 + (int64_t)random(-2000, 2001);
    if (deltaUs < 1000) deltaUs = 1000;
    proto = "tcp";
    state = "CON";
    bytes = (int)random(64, 513);
  } else {
    // Attack mode: UDP flood, inter-arrival 400us ± 5us, bytes 60
    deltaUs = 400 + (int64_t)random(-5, 6);
    if (deltaUs < 200) deltaUs = 200;
    proto = "udp";
    state = "INT";
    bytes = 60;
  }

  // Accumulate virtual clock timestamp (wrap safe 32-bit counter)
  virtualUs += (uint32_t)deltaUs;

  // Monotonic sequence number wrapping at 2^31
  uint32_t currentSeq = seqNo;
  seqNo = (seqNo + 1) & 0x7FFFFFFF;

  // Format line-delimited JSON schema v1:
  // {"v":1,"node":"ESP32-01","seq":N,"st":N,"proto":"udp","state":"INT","flgs":"e","bytes":60}\n
  char jsonBuffer[180];
  int written = snprintf(
    jsonBuffer,
    sizeof(jsonBuffer),
    "{\"v\":1,\"node\":\"%s\",\"seq\":%u,\"st\":%u,\"proto\":\"%s\",\"state\":\"%s\",\"flgs\":\"e\",\"bytes\":%d}\n",
    NODE_ID,
    (unsigned int)currentSeq,
    (unsigned int)virtualUs,
    proto,
    state,
    bytes
  );

  if (written > 0) {
    client.write((const uint8_t*)jsonBuffer, (size_t)written);
  }
}

// ============================================================================
// 10. Optional TFT Screen UI (240x240 ST7789)
// ============================================================================
#if HAS_TFT
void initTftUI() {
  tft.init();
  tft.setRotation(1);
  tft.fillScreen(TFT_BLACK);
  updateTftUI();
}

void updateTftUI() {
  tft.fillScreen(TFT_BLACK);

  // Header Bar
  tft.fillRect(0, 0, 240, 30, TFT_NAVY);
  tft.setTextColor(TFT_WHITE, TFT_NAVY);
  tft.setTextSize(2);
  tft.setCursor(8, 7);
  tft.print(F("REDFLAG ESP32"));

  // Node ID and Network
  tft.setTextSize(1);
  tft.setTextColor(TFT_LIGHTGREY, TFT_BLACK);
  tft.setCursor(8, 38);
  tft.printf("Node ID : %s", NODE_ID);

  tft.setCursor(8, 52);
  if (WiFi.status() == WL_CONNECTED) {
    tft.printf("WiFi IP : %s", WiFi.localIP().toString().c_str());
  } else {
    tft.print(F("WiFi IP : Disconnected"));
  }

  tft.setCursor(8, 66);
  tft.printf("Gateway : %s:%d", GATEWAY_HOST, GATEWAY_PORT);

  tft.setCursor(8, 80);
  tft.printf("TCP Link: %s", client.connected() ? "CONNECTED" : "DISCONNECTED");

  // Mode Indicator Box
  uint16_t boxColor = (currentMode == MODE_BENIGN) ? TFT_DARKGREEN : TFT_RED;
  tft.fillRoundRect(8, 102, 224, 76, 8, boxColor);
  tft.drawRoundRect(8, 102, 224, 76, 8, TFT_WHITE);

  tft.setTextSize(2);
  tft.setTextColor(TFT_WHITE, boxColor);
  tft.setCursor(22, 115);
  if (currentMode == MODE_BENIGN) {
    tft.print(F("BENIGN SENSOR"));
    tft.setTextSize(1);
    tft.setCursor(22, 142);
    tft.print(F("TCP/CON | ~5ms dt | Normal"));
  } else {
    tft.print(F("MIRAI UDP FLOOD"));
    tft.setTextSize(1);
    tft.setCursor(22, 142);
    tft.print(F("UDP/INT | 400us dt | ATTACK"));
  }

  // Footer / Instructions
  tft.setTextSize(1);
  tft.setTextColor(TFT_DARKGREY, TFT_BLACK);
  tft.setCursor(8, 200);
  tft.print(F("Press BOOT button (GPIO 0) to toggle"));
  tft.setCursor(8, 218);
  tft.print(F("LED: Slow=Safe | Fast=Flood"));
}
#endif

// ============================================================================
// 11. Arduino Setup & Main Loop
// ============================================================================
void setup() {
  Serial.begin(115200);
  delay(100);

  Serial.println(F("\n=================================================="));
  Serial.println(F("  PROJECT REDFLAG — ESP32 EDGE TELEMETRY FIRMWARE"));
  Serial.println(F("=================================================="));

  pinMode(BOOT_BTN_PIN, INPUT_PULLUP);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  // Initialize pseudo-random generator with entropy from esp_timer and floating ADC
  randomSeed((uint32_t)esp_timer_get_time() ^ (uint32_t)analogRead(36));

  // Seed virtual clock with randomized base time
  virtualUs = (uint32_t)random(1000000, 50000000);

  Serial.printf("[INIT] Node Identifier: %s\n", NODE_ID);
  Serial.printf("[INIT] Target Gateway:  %s:%d\n", GATEWAY_HOST, GATEWAY_PORT);
  Serial.printf("[INIT] Initial Mode:    %s\n", (currentMode == MODE_ATTACK) ? "ATTACK" : "BENIGN");

#if HAS_TFT
  initTftUI();
#endif

  // Initiate asynchronous WiFi connection
  Serial.print(F("[WIFI] Connecting to SSID: "));
  Serial.println(WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  lastWifiRetryMs = millis();

  nextEmitUs = esp_timer_get_time();
}

void loop() {
  // 1. Maintain hardware FSMs
  pollButtonFSM();
  updateLedIndicator();

  // 2. Maintain network connections
  handleWiFiConnection();
  handleTcpConnection();

  // 3. Process gateway feedback (HTTP 403 / 200)
  pollGatewayFeedback();

  // 4. Rate-paced telemetry emission using microsecond virtual clock
  int64_t nowUs = esp_timer_get_time();
  if (nowUs >= nextEmitUs) {
    if (client.connected()) {
      emitTelemetryFrame();
    }
    // Schedule next emission cadence
    int64_t intervalUs = (currentMode == MODE_BENIGN)
        ? (5000 + (int64_t)random(-2000, 2001))
        : (400 + (int64_t)random(-5, 6));

    if (intervalUs < 200) intervalUs = 200;
    nextEmitUs = nowUs + intervalUs;
  }

  // Small yield to allow background RTOS tasks (WiFi/lwIP stack) to run smoothly
  yield();
}

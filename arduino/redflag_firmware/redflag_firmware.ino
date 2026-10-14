// Project Redflag — Edge IoT Device Firmware
// Targets: Arduino Uno (ATmega328P) / Mega 2560 + MCUFRIEND TFT Touch Shield
// Emulates benign IoT sensor telemetry (TCP/CON) and volumetric Mirai UDP floods
// ============================================================================

#include <Adafruit_GFX.h>
#include <MCUFRIEND_kbv.h>
#include <TouchScreen.h>

MCUFRIEND_kbv tft;

// ---- Touch Wiring (Standard MCUFRIEND Uno Shield) ----
// Note: XM (A2) and YP (A3) are shared with LCD control lines (LCD_RS and LCD_CS).
// Pin modes MUST be restored after every ts.getPoint() before drawing to LCD.
const int XP = 8, XM = A2, YP = A3, YM = 9;
const int TS_LEFT = 120, TS_RT = 920, TS_TOP = 70, TS_BOT = 900;
const int MINPRESSURE = 150, MAXPRESSURE = 1000;
TouchScreen ts(XP, YP, XM, YM, 300);

// ---- 16-bit 565 Color Definitions ----
#define BLACK   0x0000
#define BLUE    0x001F
#define RED     0xF800
#define GREEN   0x07E0
#define CYAN    0x07FF
#define WHITE   0xFFFF
#define GREY    0x7BEF
#define DARKGREY 0x2104

// ---- Telemetry Profile & State ----
enum Mode : uint8_t { SAFE = 0, ATTACK = 1 };
volatile Mode mode = SAFE;
const char NODE_ID[] = "UNO-01";

// Timing intervals
const unsigned long BENIGN_PERIOD_MS = 1000;
const unsigned long ATTACK_PERIOD_MS = 10;      // 10ms emission cadence (100 pkt/sec line rate)
const uint32_t ATTACK_VIRT_DT_US     = 400;     // Virtual inter-arrival (~0.4 ms line rate)
const uint32_t REARM_GAP_US          = 6000000; // 6 s link re-association gap to reset session

uint32_t seqNo = 0;
uint32_t virtualUs = 0;
unsigned long lastEmitMs = 0;
unsigned long nextDueMs = 0;

// ---- Non-blocking Touch Debounce FSM ----
enum TouchState : uint8_t { T_IDLE, T_CANDIDATE, T_COOLDOWN };
TouchState tState = T_IDLE;
unsigned long tStamp = 0;
const unsigned long DEBOUNCE_MS = 25;
const unsigned long COOLDOWN_MS = 300;

// UI Geometry
int btnX, btnY, btnW, btnH;

// Touch reading with mandatory LCD pin restoration
bool readTouch(int &sx, int &sy) {
  TSPoint p = ts.getPoint();
  // CRITICAL: Hand control pins back to LCD controller immediately
  pinMode(XM, OUTPUT);
  pinMode(YP, OUTPUT);

  if (p.z < MINPRESSURE || p.z > MAXPRESSURE) {
    return false;
  }
  // Rotation 1 (Landscape): map coordinates to screen pixels
  sx = map(p.y, TS_TOP, TS_BOT, 0, tft.width());
  sy = map(p.x, TS_RT,  TS_LEFT, 0, tft.height());
  return true;
}

bool inButton(int x, int y) {
  return (x >= btnX && x <= (btnX + btnW) && y >= btnY && y <= (btnY + btnH));
}

void drawUI() {
  tft.fillScreen(BLACK);

  // Header Title
  tft.fillRect(0, 0, tft.width(), 32, DARKGREY);
  tft.setTextColor(WHITE);
  tft.setTextSize(2);
  tft.setCursor(12, 8);
  tft.print(F("REDFLAG IoT GATEWAY"));

  // Initial Status Banner (row below title)
  tft.fillRect(0, 36, tft.width(), 26, BLACK);
  tft.setTextColor(GREY);
  tft.setTextSize(2);
  tft.setCursor(12, 40);
  tft.print(F("GATEWAY: MONITORING"));

  // Main Mode Toggle Button
  uint16_t btnColor = (mode == SAFE) ? GREEN : RED;
  tft.fillRoundRect(btnX, btnY, btnW, btnH, 10, btnColor);
  tft.drawRoundRect(btnX, btnY, btnW, btnH, 10, WHITE);

  tft.setTextColor(BLACK);
  tft.setTextSize(2);
  tft.setCursor(btnX + 16, btnY + 18);
  if (mode == SAFE) {
    tft.print(F("NORMAL IoT SENSOR"));
    tft.setCursor(btnX + 16, btnY + 44);
    tft.setTextSize(1);
    tft.print(F("1 pkt/sec | TAP TO TRIGGER ATTACK"));
  } else {
    tft.setTextColor(WHITE);
    tft.print(F("MIRAI UDP FLOOD"));
    tft.setCursor(btnX + 16, btnY + 44);
    tft.setTextSize(1);
    tft.print(F("Line-rate bursts | TAP TO RESTORE"));
  }

  // Footer Info
  tft.setTextColor(GREY);
  tft.setTextSize(1);
  tft.setCursor(12, tft.height() - 20);
  tft.print(F("115200 baud | JSON Schema v1 | Mirai Edge Guard"));
}

void toggleMode() {
  if (mode == SAFE) {
    mode = ATTACK;
  } else {
    mode = SAFE;
    // Step virtual time ahead by 6 seconds so the gateway flushes the session buffer
    virtualUs += REARM_GAP_US;
  }
  drawUI();
}

void pollTouch() {
  int x, y;
  bool touching = readTouch(x, y) && inButton(x, y);
  unsigned long now = millis();

  switch (tState) {
    case T_IDLE:
      if (touching) {
        tState = T_CANDIDATE;
        tStamp = now;
      }
      break;
    case T_CANDIDATE:
      if (!touching) {
        tState = T_IDLE; // Contact bounce/chatter
      } else if (now - tStamp >= DEBOUNCE_MS) {
        toggleMode();
        tState = T_COOLDOWN;
        tStamp = now;
      }
      break;
    case T_COOLDOWN:
      if (!touching && (now - tStamp >= COOLDOWN_MS)) {
        tState = T_IDLE;
      }
      break;
  }
}

// Emits structured JSON telemetry frame over USB Serial
void emitFrame() {
  char buf[140];
  uint16_t bytes;
  const char *proto, *state;

  if (mode == SAFE) {
    proto = "tcp";
    state = "CON";
    bytes = 114 + random(0, 13); // 120 +/- 6 bytes
  } else {
    proto = "udp";
    state = "INT";
    bytes = 58 + random(0, 5);   // 60 +/- 2 bytes
  }

  int n = snprintf(buf, sizeof(buf),
      "{\"v\":1,\"node\":\"%s\",\"seq\":%lu,\"st\":%lu,\"proto\":\"%s\",\"state\":\"%s\",\"flgs\":\"e\",\"bytes\":%u}\n",
      NODE_ID, (unsigned long)seqNo++, (unsigned long)virtualUs,
      proto, state, bytes);

  if (n > 0) {
    Serial.write((const uint8_t*)buf, (size_t)n);
  }
}

// Full duplex communication: listens for BLOCK/SAFE verdicts from the gateway
void pollSerialRx() {
  static char line[16];
  static uint8_t len = 0;

  while (Serial.available()) {
    char ch = (char)Serial.read();
    if (ch == '\n' || ch == '\r') {
      if (len > 0) {
        line[len] = 0;
        len = 0;
        uint16_t bannerColor = 0;
        bool isBlock = (strcmp(line, "BLOCK") == 0);
        bool isSafe = (strcmp(line, "SAFE") == 0);

        if (isBlock) bannerColor = RED;
        else if (isSafe) bannerColor = GREEN;

        if (bannerColor) {
          tft.fillRect(0, 36, tft.width(), 26, BLACK);
          tft.setTextColor(bannerColor);
          tft.setTextSize(2);
          tft.setCursor(12, 40);
          tft.print(isBlock ? F("MITIGATED (HTTP 403)") : F("LINK OK (HTTP 200)"));
        }
      }
    } else if (len < sizeof(line) - 1) {
      line[len++] = ch;
    }
  }
}

void setup() {
  Serial.begin(115200);

  uint16_t id = tft.readID();
  if (id == 0xD3D3 || id == 0 || id == 0xFFFF) id = 0x9341; // Fallback to ILI9341 if unreadable
  tft.begin(id);
  tft.setRotation(1); // Landscape

  btnW = tft.width() - 40;
  btnH = 80;
  btnX = 20;
  btnY = 80;

  randomSeed(analogRead(A5));
  drawUI();

  nextDueMs = millis();
  lastEmitMs = micros();
}

void loop() {
  pollTouch();
  pollSerialRx();

  unsigned long nowMs = millis();
  if ((long)(nowMs - nextDueMs) >= 0) {
    if (mode == SAFE) {
      unsigned long nowUs = micros();
      virtualUs += (uint32_t)(nowUs - lastEmitMs); // Real elapsed delta_t
      lastEmitMs = nowUs;
      nextDueMs = nowMs + BENIGN_PERIOD_MS + random(-40, 41); // Realistic jitter
    } else {
      virtualUs += (ATTACK_VIRT_DT_US + random(-5, 6));       // Machine-level tight delta_t (std ~ 3-5us)
      lastEmitMs = micros();
      nextDueMs = nowMs + ATTACK_PERIOD_MS;                   // 10 ms emission cycle
    }
    emitFrame();
  }
}

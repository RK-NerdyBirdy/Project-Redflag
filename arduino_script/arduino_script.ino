#include <Adafruit_GFX.h>
#include <MCUFRIEND_kbv.h>
#include <TouchScreen.h>

MCUFRIEND_kbv tft;

// --- PROVEN TOUCH SCREEN CONFIGURATION ---
const int XP = 8, XM = A2, YP = A3, YM = 9; 
const int TS_LEFT = 150, TS_RT = 900, TS_TOP = 120, TS_BOT = 920;

TouchScreen ts = TouchScreen(XP, YP, XM, YM, 300);

// --- Color Definitions (16-bit RGB565) ---
#define BLACK   0x0000
#define BLUE    0x001F
#define RED     0xF800
#define GREEN   0x07E0
#define CYAN    0x07FF
#define WHITE   0xFFFF
#define GRAY    0x8410

// --- Operational States ---
enum NodeMode {
  MODE_NORMAL, 
  MODE_ATTACK  
};

NodeMode currentMode = MODE_NORMAL;
unsigned long lastSendTime = 0;
unsigned long packetCounter = 0;
unsigned long lastTouchTime = 0; 

// Dynamic Button Variables
int btnX, btnY, btnW, btnH;

void drawToggleButton() {
  tft.fillRoundRect(btnX, btnY, btnW, btnH, 8, BLUE);
  tft.drawRoundRect(btnX, btnY, btnW, btnH, 8, WHITE);
  
  tft.setCursor(btnX + (btnW / 2) - 65, btnY + 17); 
  tft.setTextColor(WHITE);
  tft.setTextSize(2);
  tft.print("TOGGLE MODE");
}

void updateScreen() {
  tft.fillRect(0, 40, tft.width(), btnY - 45, BLACK); 

  if (currentMode == MODE_NORMAL) {
    tft.fillRoundRect(10, 50, tft.width() - 20, 50, 6, GREEN);
    tft.setCursor(25, 68);
    tft.setTextColor(BLACK);
    tft.setTextSize(2);
    tft.println("[NORMAL MODE]");

    tft.setCursor(15, 120);
    tft.setTextColor(GREEN);
    tft.setTextSize(2);
    tft.println("State: SAFE");
    
    tft.setCursor(15, 155);
    tft.setTextColor(WHITE);
    tft.setTextSize(1);
    tft.println("Rate: 1 packet / 1000ms");
    tft.println("Simulating: Temp Telemetry");
  } else {
    tft.fillRoundRect(10, 50, tft.width() - 20, 50, 6, RED);
    tft.setCursor(25, 68);
    tft.setTextColor(WHITE);
    tft.setTextSize(2);
    tft.println("[ATTACK MODE]");

    tft.setCursor(15, 120);
    tft.setTextColor(RED);
    tft.setTextSize(2);
    tft.println("State: FLOOD (DDoS)");

    tft.setCursor(15, 155);
    tft.setTextColor(WHITE);
    tft.setTextSize(1);
    tft.println("Rate: Burst (0-15ms gap)");
    tft.println("Simulating: Mirai Botnet");
  }

  tft.setCursor(15, btnY - 40);
  tft.setTextColor(CYAN);
  tft.setTextSize(1);
  tft.print("Packets Emitted: ");
  tft.println(packetCounter);
}

void setup() {
  Serial.begin(115200);

  uint16_t identifier = tft.readID();
  if (identifier == 0xEFEF) identifier = 0x9486; 
  tft.begin(identifier);
  tft.setRotation(0); 
  tft.fillScreen(BLACK);

  btnW = tft.width() - 20;
  btnH = 50;
  btnX = 10;
  btnY = tft.height() - 70;

  tft.setTextColor(WHITE);
  tft.setTextSize(2);
  tft.setCursor(15, 10);
  tft.println("PROJECT REDFLAG");
  tft.drawFastHLine(0, 32, tft.width(), WHITE);

  updateScreen();
  drawToggleButton();
}

void sendPacket(bool isAttack) {
  packetCounter++;
  
  if (isAttack) {
    // Malicious Mirai footprint: UDP flood, INT state
    Serial.print("{\"node_id\":\"node_01\",\"bytes\":60,\"proto\":\"udp\",\"state\":\"INT\",\"flgs\":\"e\",\"attack\":1,\"seq\":");
    Serial.print(packetCounter);
    Serial.println("}");
  } else {
    // Benign IoT footprint: TCP telemetry, CON state
    int mockTemp = 24 + (packetCounter % 5);
    Serial.print("{\"node_id\":\"node_01\",\"bytes\":120,\"proto\":\"tcp\",\"state\":\"CON\",\"flgs\":\"e\",\"attack\":0,\"temp\":");
    Serial.print(mockTemp);
    Serial.print(",\"seq\":");
    Serial.print(packetCounter);
    Serial.println("}");
  }
}

void loop() {
  unsigned long currentMillis = millis();
  TSPoint p = ts.getPoint();

  // Restore Pins for display
  pinMode(YP, OUTPUT);
  pinMode(XM, OUTPUT);

  if (p.z > 10 && p.z < 1000) {
    if (currentMillis - lastTouchTime > 500) {
      
      // INVERTED Y-AXIS MAPPING
      int pixel_x = map(p.x, TS_LEFT, TS_RT, 0, tft.width());
      int pixel_y = map(p.y, TS_BOT, TS_TOP, 0, tft.height());

      if (pixel_x > btnX && pixel_x < (btnX + btnW) && pixel_y > btnY && pixel_y < (btnY + btnH)) {
        currentMode = (currentMode == MODE_NORMAL) ? MODE_ATTACK : MODE_NORMAL;
        updateScreen();
        lastTouchTime = currentMillis; 
      }
    }
  }

  if (currentMode == MODE_NORMAL) {
    if (currentMillis - lastSendTime >= 1000) { 
      lastSendTime = currentMillis;
      sendPacket(false);
      if (packetCounter % 5 == 0) updateScreen();
    }
  } else {
    if (currentMillis - lastSendTime >= 15) { 
      lastSendTime = currentMillis;
      sendPacket(true);
      if (packetCounter % 25 == 0) updateScreen();
    }
  }
}
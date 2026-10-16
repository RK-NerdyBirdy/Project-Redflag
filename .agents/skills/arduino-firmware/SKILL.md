---
name: arduino-firmware
description: >-
  Use this skill when building or modifying the Arduino firmware for Project Redflag.
  Covers the MCUFRIEND TFT touch shield setup, pin arbitration between LCD and touch,
  debounce state machine, JSON telemetry generation with virtual clock, byte shaping,
  and serial full-duplex communication with the Java gateway.
---

# Arduino Firmware Skill — Project Redflag

## Hardware Stack
- **MCU**: Arduino Uno (ATmega328P, 16 MHz, 2 KB SRAM) or Mega 2560 (8 KB SRAM)
- **Display**: MCUFRIEND 2.4"/3.5" TFT shield (ILI9341/ILI9486, 8-bit parallel)
- **Touch**: 4-wire resistive panel via `TouchScreen` library
- **Link**: USB CDC serial, 115200 8N1

## Required Libraries (Arduino IDE)
Install via Arduino Library Manager:
1. `Adafruit_GFX` — graphics primitives
2. `MCUFRIEND_kbv` — TFT driver (auto-detects ILI9341/ILI9486 via `tft.readID()`)
3. `TouchScreen` — Adafruit resistive touch library

## Critical: Pin Arbitration Protocol

Touch lines share with LCD control pins:

| Touch | Arduino Pin | LCD Signal    |
|-------|-------------|---------------|
| YP    | A3          | LCD_CS        |
| XM    | A2          | LCD_RS / CD   |
| XP    | D8          | LCD data bit  |
| YM    | D9          | LCD data bit  |

**After EVERY `ts.getPoint()` call, IMMEDIATELY do:**
```cpp
pinMode(XM, OUTPUT);   // give LCD_RS back
pinMode(YP, OUTPUT);   // give LCD_CS back
```
Failure to restore these pins = blank/corrupted screen.

## Touch Debounce State Machine (non-blocking)

```
IDLE → CANDIDATE (pressure detected in button area)
CANDIDATE → IDLE (pressure lost < 25ms = chatter)
CANDIDATE → LATCHED (stable ≥ 25ms → toggle mode, redraw UI)
LATCHED → COOLDOWN (ignore held finger)
COOLDOWN → IDLE (released AND 300ms elapsed)
```

Constants: `DEBOUNCE_MS = 25`, `COOLDOWN_MS = 300`
**Never use `delay()`** — always `millis()` for timing.

## Telemetry Framing

JSON line format, `\n` terminated:
```json
{"v":1,"node":"UNO-01","seq":25,"st":4210500,"proto":"udp","state":"INT","flgs":"e","bytes":60}
```

### Profiles
| Mode   | proto | state | bytes        | Virtual Δt       | Wall period |
|--------|-------|-------|-------------|------------------|-------------|
| SAFE   | tcp   | CON   | 114-126 (≈120±6) | real elapsed µs | ~1000ms ± jitter |
| ATTACK | udp   | INT   | 58-62 (≈60±2)   | ~500 ± 50 µs virtual | 15ms wall |

### Virtual Clock
- Benign: advance `st` by real elapsed time (`micros()` delta)
- Attack: advance `st` by `ATTACK_VIRT_DT_US ≈ 500µs` per frame
- On mode switch ATTACK→SAFE: add `REARM_GAP_US = 6,000,000µs` to force session reset at gateway
- 32-bit wrap: the gateway unwraps when `st` rolls over

## Full Duplex: Gateway → Arduino
The gateway may send `"BLOCK\n"` or `"SAFE\n"` back. The firmware reads serial and updates the TFT status banner:
- `BLOCK` → Red banner "MITIGATED (403)"
- `SAFE` → Green banner "LINK OK (200)"

## UI Layout
- Title bar: "REDFLAG IoT GATEWAY" at top
- Main button: fills most of screen width, shows current mode and tap action
- Bottom bar: "115200 baud | JSON"
- Status banner area (row below title): shows gateway feedback

## Build & Upload Steps

1. Open `arduino/redflag_firmware/redflag_firmware.ino` in Arduino IDE
2. Select board: **Arduino Uno** or **Arduino Mega 2560**
3. Install libraries: Adafruit_GFX, MCUFRIEND_kbv, TouchScreen
4. Plug in the TFT shield, connect USB
5. Upload — the sketch auto-detects the TFT driver IC at boot
6. Open Serial Monitor at 115200 baud to verify JSON output

## Touch Calibration
If touch coordinates are off, run the `TouchScreen_Calibr_native` example from the MCUFRIEND library and update `TS_LEFT`, `TS_RT`, `TS_TOP`, `TS_BOT` constants.

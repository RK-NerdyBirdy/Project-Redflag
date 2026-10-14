"""
mock_serial.py — Edge Device Simulator for Project Redflag

Simulates the physical Arduino Uno/Mega + MCUFRIEND TFT touch shield.
Generates structured JSON v1 frames matching the exact firmware specification:
- Safe mode: TCP / CON ~120 bytes every 1000 ms with natural jitter
- Attack mode: UDP / INT ~60 bytes every 15 ms wall time with 500us virtual clock steps

Can emit to:
1. HTTP POST endpoint (default: http://localhost:8080/api/telemetry) - Uses Python standard library urllib
2. Physical or Virtual COM port (via PySerial)
3. Standard output

Usage:
    python tools/mock_serial.py
    python tools/mock_serial.py --mode http --url http://localhost:8080/api/telemetry
    python tools/mock_serial.py --mode serial --port COM8
"""

import argparse
import json
import random
import sys
import time
import urllib.request
import urllib.error

# Ensure Windows terminal doesn't crash on non-ASCII characters
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

try:
    import serial
except ImportError:
    serial = None

NODE_ID = "UNO-01"
SAFE_PERIOD_MS = 1000
ATTACK_WALL_MS = 10
ATTACK_VIRT_DT_US = 400


def send_http(url, payload_str):
    try:
        data = payload_str.encode("utf-8")
        req = urllib.request.Request(
            url,
            data=data,
            headers={"Content-Type": "application/json", "User-Agent": "RedflagSimulator/1.0"},
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=1.5) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception as e:
        return str(e)


def run_simulator(mode_type, port_or_url, baud):
    ser = None
    if mode_type == "serial":
        if not serial:
            print("ERROR: pyserial is required for serial mode. Install with: pip install pyserial")
            sys.exit(1)
        print(f"[SIMULATOR] Opening Serial Port {port_or_url} @ {baud}...")
        try:
            ser = serial.Serial(port_or_url, baud, timeout=0.1)
            time.sleep(1.5)
            print(f"[SIMULATOR] Serial port {port_or_url} open and ready.")
        except Exception as e:
            print(f"ERROR: Could not open {port_or_url}: {e}")
            print("Hint: If no physical Arduino is connected, use HTTP mode instead:")
            print("      python tools/mock_serial.py --mode http")
            sys.exit(1)

    current_mode = "SAFE"
    seq = 0
    virtual_us = int(time.time() * 1_000_000) % (2**32)
    last_emit_wall = time.time()
    next_due_wall = time.time()

    print("=" * 68)
    print("  PROJECT REDFLAG — IoT EDGE TELEMETRY SIMULATOR")
    print("=" * 68)
    print(f"  Target: {port_or_url} ({mode_type.upper()})")
    print("  Emulation pattern: 20 packets SAFE -> 100 packets ATTACK -> repeat")
    print("  Press Ctrl+C to stop.\n")

    cycle_count = 0

    try:
        while True:
            now_wall = time.time()
            if now_wall >= next_due_wall:
                cycle_count += 1
                if cycle_count <= 20:
                    current_mode = "SAFE"
                elif cycle_count <= 120:
                    current_mode = "ATTACK"
                else:
                    cycle_count = 0
                    current_mode = "SAFE"
                    virtual_us += 6_000_000  # 6s gap models session reset

                if current_mode == "SAFE":
                    elapsed_us = int((now_wall - last_emit_wall) * 1_000_000)
                    virtual_us += max(elapsed_us, 950_000)
                    proto = "tcp"
                    state = "CON"
                    payload_bytes = 114 + random.randint(0, 12)
                    next_due_wall = now_wall + (SAFE_PERIOD_MS + random.randint(-40, 40)) / 1000.0
                else:
                    virtual_us += ATTACK_VIRT_DT_US + random.randint(-5, 5)
                    proto = "udp"
                    state = "INT"
                    payload_bytes = 58 + random.randint(0, 4)
                    next_due_wall = now_wall + (ATTACK_WALL_MS / 1000.0)

                last_emit_wall = now_wall
                frame = {
                    "v": 1,
                    "node": NODE_ID,
                    "seq": seq,
                    "st": virtual_us % (2**32),
                    "proto": proto,
                    "state": state,
                    "flgs": "e",
                    "bytes": payload_bytes
                }
                seq += 1
                payload_str = json.dumps(frame)

                status_msg = ""
                if mode_type == "serial" and ser:
                    ser.write((payload_str + "\n").encode("ascii"))
                    if ser.in_waiting:
                        rx = ser.readline().decode("ascii", errors="ignore").strip()
                        if rx:
                            print(f"  <-- [FEEDBACK FROM GATEWAY]: {rx}")
                elif mode_type == "http":
                    status = send_http(port_or_url, payload_str)
                    status_msg = f" -> HTTP {status}"
                    if status != 202 and status != 200:
                        status_msg += " (Make sure Gateway is running on port 8080!)"

                mode_symbol = "[+ SAFE ]" if current_mode == "SAFE" else "[! ATTACK]"
                print(f"{mode_symbol} Seq #{seq:4d} | st: {frame['st']:10d} | {proto.upper():4s}/{state:3s} | {payload_bytes:3d}B{status_msg}", flush=True)

            time.sleep(0.005)

    except KeyboardInterrupt:
        print("\n[SIMULATOR] Stopped by user.", flush=True)
        if ser:
            ser.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Project Redflag Telemetry Simulator")
    parser.add_argument("--mode", choices=["http", "serial", "stdout"], default="http", help="Output mode (default: http)")
    parser.add_argument("--port", default="COM8", help="Serial port name (for serial mode)")
    parser.add_argument("--url", default="http://localhost:8080/api/telemetry", help="HTTP URL (default: http://localhost:8080/api/telemetry)")
    parser.add_argument("--baud", type=int, default=115200, help="Baud rate")
    args = parser.parse_args()

    target = args.port if args.mode == "serial" else (args.url if args.mode == "http" else "stdout")
    run_simulator(args.mode, target, args.baud)

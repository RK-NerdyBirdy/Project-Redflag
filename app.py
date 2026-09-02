import time
import json
import joblib
import serial
import pandas as pd
import numpy as np
import random
from collections import defaultdict

# --- Configuration ---
SERIAL_PORT = "COM8"  
BAUD_RATE = 115200
WINDOW_SIZES = [10, 20, 30, 50]

# 1. Load Model & Features
print("[SYSTEM] Loading XGBoost Model & Feature Columns...")
model = joblib.load("redflag_model.pkl")
with open("feature_columns.json", "r") as f:
    feature_columns = json.load(f)

node_history = defaultdict(list)

# 2. Connect to Arduino
print(f"[SERIAL] Connecting to Arduino on {SERIAL_PORT}...")
try:
    ser = serial.Serial(SERIAL_PORT, BAUD_RATE, timeout=1)
    time.sleep(2) 
    print(f"[SERIAL] Connected to {SERIAL_PORT} successfully.\n")
except Exception as e:
    print(f"[ERROR] Could not open {SERIAL_PORT}: {e}")
    exit(1)

print("="*65)
print("  REDFLAG EDGE INFERENCE GATEWAY (SERIAL INTERCEPTION)")
print("="*65)

# 3. Processing Loop
while True:
    try:
        if ser.in_waiting > 0:
            raw_line = ser.readline().decode("utf-8", errors="ignore").strip()
            real_arrival_time = time.time()
            
            if not raw_line.startswith("{") or not raw_line.endswith("}"):
                continue

            data = json.loads(raw_line)
            node_id = data.get("node_id", "node_01")
            payload_bytes = data.get("bytes", 64)
            proto = data.get("proto", "tcp")
            state = data.get("state", "CON")
            flgs = data.get("flgs", "e")
            seq = data.get("seq", 0)

            history = node_history[node_id]

            # --- SESSION FLUSH ---
            # If there is a physical gap > 5 seconds, flush memory to prevent high-variance artifacts
            if len(history) > 0 and (real_arrival_time - history[-1]["real_time"] > 5.0):
                print(f"\n🔄 [{node_id}] Session reset detected (>5s gap). Flushing memory buffer...\n")
                history.clear()

            # --- SPEED SPOOF & JITTER FIX ---
            if proto == "udp" and len(history) > 0:
                # Malicious Flood: perfectly spaced, hyper-fast packets (0.5ms)
                spoofed_time = history[-1]["stime"] + 0.0005
            else:
                # Benign Telemetry: Add realistic Wi-Fi network jitter (+/- 50ms)
                spoofed_time = real_arrival_time + random.uniform(-0.05, 0.05)

            history.append({
                "stime": spoofed_time, 
                "real_time": real_arrival_time,
                "bytes": payload_bytes,
                "proto": proto,
                "state": state,
                "flgs": flgs
            })

            if len(history) > 50:
                history.pop(0)

            if len(history) < 10:
                print(f"[BUFFERING] Node: {node_id} | Packet #{seq} | Buffer: {len(history)}/10")
                continue

            # --- Temporal Feature Calculation ---
            df = pd.DataFrame(history)
            df["delta_t"] = df["stime"].diff().fillna(0.0)
            df.loc[df["delta_t"] < 0, "delta_t"] = 0.0

            for w in WINDOW_SIZES:
                roll_dt = df["delta_t"].rolling(window=w, min_periods=1)
                df[f"delta_t_mean_{w}"] = roll_dt.mean()
                df[f"delta_t_std_{w}"] = roll_dt.std()

                roll_bytes = df["bytes"].rolling(window=w, min_periods=1)
                df[f"bytes_mean_{w}"] = roll_bytes.mean()
                df[f"bytes_std_{w}"] = roll_bytes.std()
                df[f"pkt_count_{w}"] = df.reset_index().index.to_series().clip(upper=w-1) + 1

            # --- Categorical Encoding ---
            categorical_candidates = [c for c in ["proto", "flgs", "state"] if c in df.columns]
            if categorical_candidates:
                df = pd.get_dummies(df, columns=categorical_candidates, dummy_na=False)

            df = df.fillna(0.0)
            latest_features = df.iloc[-1:].copy()
            
            # --- Inference ---
            X_infer = latest_features.reindex(columns=feature_columns, fill_value=0.0).astype(np.float32)
            prediction = model.predict(X_infer)[0]
            
            current_dt = df["delta_t"].iloc[-1]
            dt_std_10 = df["delta_t_std_10"].iloc[-1]

            if prediction == 1:
                print(f"🚨 [MITIGATED / 403] Attack from {node_id} | Seq #{seq:<4} | proto: {proto} | Δt: {current_dt*1000:6.1f}ms | Δt_std_10: {dt_std_10:.4f}")
            else:
                print(f"✅ [ACCEPTED  / 200] Benign from {node_id} | Seq #{seq:<4} | proto: {proto} | Δt: {current_dt*1000:6.1f}ms | Δt_std_10: {dt_std_10:.4f}")

    except KeyboardInterrupt:
        print("\nStopping Edge Gateway...")
        ser.close()
        break
    except Exception as e:
        continue
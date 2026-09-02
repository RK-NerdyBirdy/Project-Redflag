"""
train_model.py — standalone, reproducible training script for Project Redflag.

Trains an XGBoost classifier on the BoT-IoT dataset using leakage-safe, short-window temporal
features (inter-arrival time + byte-volume statistics over the last N packets per source), and
serializes the model to both native XGBoost JSON and joblib pickle formats.

Usage:
    python scripts/train_model.py --data /path/to/botiot.csv --out models/

This mirrors the logic in the training notebook (Project Redflag) so the model can be reproduced
outside of Kaggle.
"""
import argparse
import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import xgboost as xgb
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, confusion_matrix, f1_score

WINDOW_SIZES = [10, 20, 30, 50]

COLUMN_ALIASES = {
    "src_ip": "saddr", "srcip": "saddr", "source_ip": "saddr",
    "dst_ip": "daddr", "dstip": "daddr", "dest_ip": "daddr",
    "src_mac": "smac", "dst_mac": "dmac",
    "timestamp": "stime", "start_time": "stime", "time": "stime",
    "tot_bytes": "bytes", "totbytes": "bytes", "flow_bytes": "bytes",
    "label": "attack", "target": "attack",
}

LEAKAGE_COLUMNS = [
    "saddr", "daddr", "smac", "dmac", "sport", "dport",
    "stime", "ltime", "pkseqid", "seq", "category", "subcategory",
]


def load_dataset(data_path: str) -> pd.DataFrame:
    path = Path(data_path)
    if path.is_dir():
        frames = [pd.read_csv(p, low_memory=False) for p in sorted(path.glob("*.csv"))]
        df = pd.concat(frames, ignore_index=True)
    else:
        df = pd.read_csv(path, low_memory=False)

    df.columns = [c.strip().lower() for c in df.columns]
    df = df.rename(columns={k: v for k, v in COLUMN_ALIASES.items() if k in df.columns})

    required = ["saddr", "stime", "bytes", "attack"]
    missing = [c for c in required if c not in df.columns]
    if missing:
        raise KeyError(f"Missing required column(s) {missing}; available: {list(df.columns)}")

    df["stime"] = pd.to_numeric(df["stime"], errors="coerce")
    df["bytes"] = pd.to_numeric(df["bytes"], errors="coerce")
    df["attack"] = pd.to_numeric(df["attack"], errors="coerce").fillna(0).astype(int)
    df = df.dropna(subset=["saddr", "stime", "bytes"]).reset_index(drop=True)
    return df


def engineer_temporal_features(data: pd.DataFrame, windows=WINDOW_SIZES) -> pd.DataFrame:
    data = data.sort_values(["saddr", "stime"], kind="mergesort").reset_index(drop=True)
    grouped = data.groupby("saddr", sort=False)

    data["delta_t"] = grouped["stime"].diff().fillna(0.0)
    data.loc[data["delta_t"] < 0, "delta_t"] = 0.0

    grouped = data.groupby("saddr", sort=False)
    for w in windows:
        roll_dt = grouped["delta_t"].rolling(window=w, min_periods=1)
        data[f"delta_t_mean_{w}"] = roll_dt.mean().reset_index(level=0, drop=True)
        data[f"delta_t_std_{w}"] = roll_dt.std().reset_index(level=0, drop=True)

        roll_bytes = grouped["bytes"].rolling(window=w, min_periods=1)
        data[f"bytes_mean_{w}"] = roll_bytes.mean().reset_index(level=0, drop=True)
        data[f"bytes_std_{w}"] = roll_bytes.std().reset_index(level=0, drop=True)

        data[f"pkt_count_{w}"] = grouped.cumcount().clip(upper=w - 1) + 1

    engineered_cols = [c for c in data.columns if any(
        c.startswith(p) for p in ("delta_t_mean", "delta_t_std", "bytes_mean", "bytes_std", "pkt_count")
    )]
    data[engineered_cols] = data[engineered_cols].fillna(0.0)
    return data


def build_feature_matrix(df_feat: pd.DataFrame):
    drop_cols = [c for c in LEAKAGE_COLUMNS if c in df_feat.columns]
    feature_df = df_feat.drop(columns=drop_cols)

    categorical_candidates = [c for c in ["proto", "flgs", "state"] if c in feature_df.columns]
    if categorical_candidates:
        feature_df = pd.get_dummies(feature_df, columns=categorical_candidates, dummy_na=False)

    y = feature_df["attack"].astype(int)
    X = feature_df.drop(columns=["attack"])
    non_numeric = X.select_dtypes(exclude=[np.number, bool]).columns.tolist()
    if non_numeric:
        X = X.drop(columns=non_numeric)
    X = X.astype(np.float32)
    return X, y


def main():
    parser = argparse.ArgumentParser(description="Train the Project Redflag XGBoost model.")
    parser.add_argument("--data", required=True, help="Path to a BoT-IoT CSV file or a directory of CSV shards.")
    parser.add_argument("--out", default="models", help="Output directory for model artifacts.")
    parser.add_argument("--test-size", type=float, default=0.20)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    print("Loading dataset...")
    df = load_dataset(args.data)

    print("Engineering temporal features...")
    df_feat = engineer_temporal_features(df)

    X, y = build_feature_matrix(df_feat)
    print(f"Feature matrix: {X.shape}")

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=args.test_size, random_state=args.seed, stratify=y
    )

    n_pos = int((y_train == 1).sum())
    n_neg = int((y_train == 0).sum())
    scale_pos_weight = (n_neg / n_pos) if n_pos > 0 else 1.0
    print(f"scale_pos_weight = {scale_pos_weight:.4f}")

    model = xgb.XGBClassifier(
        n_estimators=400,
        max_depth=6,
        learning_rate=0.08,
        subsample=0.85,
        colsample_bytree=0.85,
        objective="binary:logistic",
        eval_metric="aucpr",
        scale_pos_weight=scale_pos_weight,
        tree_method="hist",
        random_state=args.seed,
        n_jobs=-1,
    )
    model.fit(X_train, y_train, eval_set=[(X_test, y_test)], verbose=50)

    y_pred = model.predict(X_test)
    print(classification_report(y_test, y_pred, target_names=["Benign", "Malicious"]))
    print(confusion_matrix(y_test, y_pred))
    print(f"Minority-class F1: {f1_score(y_test, y_pred, pos_label=1):.4f}")

    model.save_model(str(out_dir / "redflag_model.json"))
    joblib.dump(model, out_dir / "redflag_model.pkl")
    with open(out_dir / "feature_columns.json", "w") as f:
        json.dump(list(X.columns), f, indent=2)

    print(f"Artifacts written to {out_dir.resolve()}")


if __name__ == "__main__":
    main()
# Project Redflag

XGBoost-based botnet / volumetric-DDoS traffic classifier for **Project Trinetra**, an IoT edge
gateway for real-time attack detection. Trained on the BoT-IoT dataset using leakage-safe,
short-window temporal features — deliberately excluding raw IP/MAC identifiers and long-term
traffic averages, so the model learns attack *behavior* rather than memorizing testbed hosts.

## How it works

For each packet, we compute:
- `delta_t` — inter-arrival time since the previous packet from the same source.
- Rolling **mean/std of `delta_t`** and **mean/std of `bytes`** over the last *N* packets from
  that source, for `N in {{10, 20, 30, 50}}`.
- A rolling packet count.

Raw identifiers (`saddr`, `daddr`, `smac`, `dmac`, `sport`, `dport`, raw timestamps) are dropped
before training to prevent the model from keying off testbed-specific hosts instead of traffic
behavior.

## Model performance

Metrics from the most recent training run (see `models/metrics.json` for the full record):

| Metric | Value |
|---|---|
| Minority-class (Malicious) F1 | {minority_f1:.4f} |
| ROC-AUC | {roc_auc:.4f} |
| Accuracy | {accuracy:.4f} |
| Precision (Malicious) | {precision_malicious:.4f} |
| Recall (Malicious) | {recall_malicious:.4f} |
| Train / test size | {n_train} / {n_test} |
| Features | {n_features} |
| Trained at (UTC) | {trained_at_utc} |

Confusion matrix (rows = actual, cols = predicted, order = [Benign, Malicious]):

```
{confusion_matrix}
```

## Repository layout

```
models/
  redflag_model.json     # Native XGBoost JSON — loadable via XGBoost4J (JVM) for edge inference
  redflag_model.pkl       # joblib-pickled XGBClassifier for Python inference/testing
  feature_columns.json    # Exact ordered feature list the model expects
  metrics.json            # Full evaluation metrics from the training run
scripts/
  train_model.py          # Standalone, reproducible training script (mirrors the notebook)
requirements.txt
README.md
```

## Running inference (Python)

```python
import joblib
import json
import pandas as pd

model = joblib.load("models/redflag_model.pkl")
feature_columns = json.load(open("models/feature_columns.json"))

# `X_new` must contain exactly `feature_columns`, in order, with the same
# temporal-window engineering applied (see scripts/train_model.py).
X_new = X_new[feature_columns]
predictions = model.predict(X_new)
probabilities = model.predict_proba(X_new)[:, 1]
```

## Running inference (JVM / XGBoost4J)

```java
Booster booster = XGBoost.loadModel("models/redflag_model.json");
```

## Reproducing training

```bash
pip install -r requirements.txt
python scripts/train_model.py --data /path/to/botiot.csv --out models/
```

## Provenance

Auto-generated and pushed from a Kaggle training notebook on {trained_at_utc} as part of
Project Trinetra (IoT edge gateway for real-time botnet/DDoS detection).

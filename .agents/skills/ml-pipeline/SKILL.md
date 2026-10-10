---
name: ml-pipeline
description: >-
  Use this skill when training or retraining the XGBoost model for Project Redflag.
  Covers the BoT-IoT dataset preparation, leakage-free feature engineering, temporal
  feature computation, model training with class imbalance handling, evaluation, and
  export of model artifacts for the Java gateway.
---

# ML Training Pipeline Skill — Project Redflag

## Goal
Train a binary XGBoost classifier: **Benign (0) vs Attack (1)** for volumetric DDoS detection.

## Dataset
- **BoT-IoT** dataset (N. Koroniotis et al., 2019)
- ~72 million records, heavily imbalanced (99.99% attack)
- Download: https://research.unsw.edu.au/projects/bot-iot-dataset

## Leakage-Free Feature Engineering (CRITICAL)

### Excluded Features (would leak future information)
| Group | Examples | Reason |
|-------|----------|--------|
| Flow duration/rates | `dur`, `rate`, `srate`, `drate` | Computed over finished flow |
| Argus aggregates | `sum`, `mean`, `min`, `max`, `stddev` | Flow-level aggregates |
| Transaction stats | `TnP_*` | Dataset-wide counters |
| Rate stats | `AR_*` | Dataset-wide counters |
| Identifiers | `pkSeqID`, `saddr`, `daddr`, `sport`, `dport`, `seq` | Memorization |

### Retained/Engineered Features (51 total)
1. **Per-packet** (2): `delta_t` (inter-arrival ms), `bytes`
2. **Rolling windows** W ∈ {10, 20, 30, 50} × 5 stats (20):
   - `delta_t_mean_W`, `delta_t_std_W`, `delta_t_max_W`, `bytes_mean_W`, `pkt_count_W`
3. **One-hot categoricals** (29):
   - `proto_*` (8), `state_*` (12), `flgs_*` (9)

### Edge-Reproducibility Rule
> Every retained feature MUST be computable by the Java gateway from fields in a single frame plus its own bounded history.

## Training Script Reference

```python
import xgboost as xgb

pos = (y_train == 1).sum()
neg = (y_train == 0).sum()

clf = xgb.XGBClassifier(
    n_estimators=300, max_depth=6, learning_rate=0.1,
    subsample=0.8, colsample_bytree=0.8,
    scale_pos_weight=neg / pos,       # positive = attack class
    tree_method="hist", eval_metric="auc",
    n_jobs=4, random_state=42
)
clf.fit(X_train[feature_names], y_train,
        eval_set=[(X_val[feature_names], y_val)])

# Export for Java gateway
clf.get_booster().save_model("models/redflag_xgb.json")

import json
json.dump(feature_names, open("models/feature_names.json", "w"))
```

## Required Exports
1. `models/redflag_xgb.json` — native XGBoost JSON model (loadable by XGBoost4J)
2. `models/feature_names.json` — ordered list of 51 feature column names

## Target Metrics
| Metric | Target |
|--------|--------|
| ROC-AUC | ≥ 0.999 |
| FPR on benign streams | 0% |
| Attack Recall | ≥ 99.9% |

## Evaluation Notes
- Stratified, time-aware split (no random shuffle across flows)
- Evaluate on benign-only hold-out to confirm FPR=0%
- Feature importance dominated by `delta_t_std_20` and `delta_t_std_10` (rate regularity)

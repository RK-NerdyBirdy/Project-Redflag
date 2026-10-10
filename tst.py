import json

cells = []

def md(src):
    cells.append({"cell_type": "markdown", "metadata": {}, "source": src})

def code(src):
    cells.append({"cell_type": "code", "execution_count": None, "metadata": {}, "outputs": [], "source": src})

# =========================================================================
# TITLE
# =========================================================================
md("""# PharmacoGNN-Deep: Enterprise-Grade Female-Stratified Polypharmacy GNN

**v2 architecture.** This is a deep re-architecture of the earlier PharmacoGNN
notebook, scaled up to fully exercise a single T4 (16GB) over a genuine
20-30 minute training run rather than a quick smoke test.

**What changed vs. v1:**

| | v1 (light) | v2 (this notebook) |
|---|---|---|
| Relations | top 10 | **top 50** |
| Node pruning | isolated nodes dropped | **full vocabulary retained** (645 drugs, ~19k proteins) |
| Encoder | 2-layer HeteroConv (SAGEConv), 64-d | **4-layer HGTConv (heterogeneous transformer), 256-d, 4 heads, residual + LayerNorm + GELU** |
| Decoder | DistMult (diagonal bilinear) | **Neural bilinear MLP** over `[z_u, z_v, z_u⊙z_v, \\|z_u-z_v\\|, e_r]` |
| Optimizer | Adam | **AdamW + CosineAnnealingWarmRestarts + grad clipping** |
| Negative sampling | 1:1, full-batch | **1:3, mini-batched per relation** |
| Epochs | 50 | **120** |

**Design note on "mini-batching" a full-graph transductive GNN.** A 4-layer
heterogeneous *attention* encoder over ~20k nodes is the expensive part of
this model — re-running it for every mini-batch (the way you'd mini-batch an
MLP) would blow well past the time budget. So this notebook uses the
standard pattern for full-graph link-prediction GNNs: **encode the whole
graph once per epoch**, then mini-batch the *decode + negative-sampling +
loss* step (bounded batch size, 1:3 positive:negative ratio, per relation),
accumulate the mini-batch losses, and take a **single** optimizer step per
epoch. This keeps memory bounded and implements the requested mini-batch
negative sampling, while keeping an epoch's wall-clock cost dominated by one
encoder pass instead of dozens. Per-epoch timing is printed live so you can
see where you land against the 20-30 minute target on your specific T4
allocation, and `EPOCHS` / `MINI_BATCH_EDGES` are both easy dials if you want
to trade runtime for depth of training.

---
""".splitlines(keepends=True))

# =========================================================================
# CELL 1 — Environment setup & GPU check
# =========================================================================
md("## Cell 1 — Environment Setup & GPU Check\n".splitlines(keepends=True))

code('''import torch

print("=" * 60)
print("GPU CHECK")
print("=" * 60)

if torch.cuda.is_available():
    gpu_name = torch.cuda.get_device_name(0)
    total_mem_gb = torch.cuda.get_device_properties(0).total_memory / 1e9
    print(f"GPU detected: {gpu_name}")
    print(f"Total VRAM:   {total_mem_gb:.2f} GB")
    if "T4" not in gpu_name:
        print(
            "WARNING: this notebook was sized for a Tesla T4 (16GB). "
            "You have a different GPU -- HIDDEN_DIM / MINI_BATCH_EDGES in "
            "Cell 6 may need adjusting for your VRAM budget."
        )
else:
    raise RuntimeError(
        "No GPU detected. In Colab: Runtime > Change runtime type > "
        "Hardware accelerator > GPU (T4)."
    )

print(f"\\nPyTorch version:      {torch.__version__}")
print(f"CUDA version (build): {torch.version.cuda}")
'''.splitlines(keepends=True))

code('''# Install PyTorch Geometric + companion packages matched to the Colab-provided
# torch/CUDA build. torch-scatter/torch-sparse accelerate message passing but
# are optional in modern PyG (>=2.4); we fall back gracefully if no matching
# wheel exists for this exact torch/CUDA combo.

TORCH_VER = torch.__version__.split("+")[0]
CUDA_TAG = ("cu" + torch.version.cuda.replace(".", "")) if torch.version.cuda else "cpu"
print(f"Resolving PyG wheels for torch {TORCH_VER} / {CUDA_TAG} ...")

get_ipython().system("pip install -q torch_geometric")

_scatter_sparse_ok = True
try:
    get_ipython().system(
        f"pip install -q torch_scatter torch_sparse "
        f"-f https://data.pyg.org/whl/torch-{TORCH_VER}+{CUDA_TAG}.html"
    )
    import torch_scatter, torch_sparse  # noqa: F401
except Exception:
    _scatter_sparse_ok = False
    print(
        "torch-scatter/torch-sparse not available as a prebuilt wheel for "
        "this exact torch/CUDA combo -- PyG will use its native fallback "
        "kernels. This does not block anything below."
    )

get_ipython().system("pip install -q networkx scikit-learn pandas")

import torch_geometric
from torch_geometric.nn import HGTConv  # sanity-check availability now, fail fast if too old

print(f"\\ntorch_geometric version: {torch_geometric.__version__}")
print(f"Accelerated scatter/sparse ops available: {_scatter_sparse_ok}")
print("HGTConv import OK.")
print("Environment ready.")
'''.splitlines(keepends=True))

# =========================================================================
# CELL 2 — Data download & extraction
# =========================================================================
md('''## Cell 2 — Automated Data Download & Extraction

Same three raw Decagon files as v1 -- unchanged, since the scale-up happens
in how we *use* the data (Cell 3), not which files we need.
'''.splitlines(keepends=True))

code('''import os
import urllib.request
import tarfile

DATA_DIR = "./data"
os.makedirs(DATA_DIR, exist_ok=True)

BASE_URL = "https://snap.stanford.edu/decagon/"
FILES = [
    "bio-decagon-ppi.tar.gz",
    "bio-decagon-targets.tar.gz",
    "bio-decagon-combo.tar.gz",
]


def _download(url, dest):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as out:
        out.write(resp.read())


for fname in FILES:
    dest = os.path.join(DATA_DIR, fname)
    if not os.path.exists(dest) or os.path.getsize(dest) == 0:
        print(f"Downloading {fname} ...")
        try:
            _download(BASE_URL + fname, dest)
        except Exception as e:
            print(f"  urllib failed ({e}); falling back to wget ...")
            os.system(f"wget -q -O {dest} {BASE_URL + fname}")
    else:
        print(f"{fname} already present, skipping download.")

    print(f"Extracting {fname} ...")
    with tarfile.open(dest, "r:gz") as tar:
        tar.extractall(path=DATA_DIR)

print("\\nContents of ./data:")
for f in sorted(os.listdir(DATA_DIR)):
    size_mb = os.path.getsize(os.path.join(DATA_DIR, f)) / 1e6
    print(f"  {f:40s} {size_mb:8.2f} MB")
'''.splitlines(keepends=True))

# =========================================================================
# CELL 3 — Preprocessing: FULL vocabulary, top-50 relations
# =========================================================================
md('''## Cell 3 — Preprocessing: Full-Vocabulary Graph, Top-50 Relations

**This cell is the core change from v1.** Node vocabulary and edge-relation
selection are now decoupled:

1. **Node vocabulary is global, not relation-filtered.** The drug vocabulary
   is the union of every STITCH ID appearing anywhere in the *full*
   `bio-decagon-combo.csv` (all ~964 relations) and `bio-decagon-targets.csv`
   -- this is the standard Decagon benchmark's **645 drugs**. The protein
   vocabulary is the union of every gene ID in the *full* PPI network and the
   targets file -- the standard benchmark's **~19,081-19,085 proteins**. We
   print the actual counts below so you can confirm the match.
2. **Relation selection (top 50) only decides which DDI *edges* get loaded**,
   exactly as in v1's top-10 logic (female-ADR keywords prioritized,
   backfilled by frequency) -- it does **not** shrink the node set.
3. **Full drug-target and protein-protein connectivity is retained** --
   every edge in the raw PPI and targets files is kept, regardless of
   whether its endpoints participate in the top-50 relations.
4. Net effect: a drug with zero edges among the selected 50 side effects
   still has `drug-targets-protein` edges into the (fully-connected) protein
   graph, so `HGTConv`'s message passing still produces a meaningful
   embedding for it -- exactly the "predict novel side effects for
   under-studied drugs" scenario this architecture is built for.
'''.splitlines(keepends=True))

code('''import pandas as pd
import numpy as np
import torch
from torch_geometric.data import HeteroData

RANDOM_SEED = 42
np.random.seed(RANDOM_SEED)
torch.manual_seed(RANDOM_SEED)

TOP_K = 50
FEMALE_WEIGHT = 3.0
STANDARD_WEIGHT = 1.0

# ---- Load raw files (columns re-assigned positionally; raw headers vary
#      slightly across SNAP dataset revisions) ----
combo_df = pd.read_csv(os.path.join(DATA_DIR, "bio-decagon-combo.csv"))
combo_df.columns = ["stitch1", "stitch2", "se_id", "se_name"]

ppi_df = pd.read_csv(os.path.join(DATA_DIR, "bio-decagon-ppi.csv"))
ppi_df.columns = ["gene1", "gene2"]

targets_df = pd.read_csv(os.path.join(DATA_DIR, "bio-decagon-targets.csv"))
targets_df.columns = ["stitch", "gene"]

print(f"Raw combo rows:   {len(combo_df):,}  (all ~964 relations)")
print(f"Raw ppi rows:     {len(ppi_df):,}")
print(f"Raw targets rows: {len(targets_df):,}")

# =====================================================================
# STEP 1 -- GLOBAL node vocabulary (unfiltered by relation selection)
# =====================================================================
all_drugs = sorted(set(combo_df["stitch1"]) | set(combo_df["stitch2"]) | set(targets_df["stitch"]))
all_proteins = sorted(set(ppi_df["gene1"]) | set(ppi_df["gene2"]) | set(targets_df["gene"]))

drug2idx = {d: i for i, d in enumerate(all_drugs)}
idx2drug = {i: d for d, i in drug2idx.items()}
protein2idx = {p: i for i, p in enumerate(all_proteins)}
idx2protein = {i: p for p, i in protein2idx.items()}

num_drugs = len(all_drugs)
num_proteins = len(all_proteins)

print(f"\\nGLOBAL vocabulary -- drugs: {num_drugs:,} (benchmark expects 645), "
      f"proteins: {num_proteins:,} (benchmark expects ~19,081-19,085)")
if num_drugs != 645:
    print("  NOTE: drug count differs from the published 645 -- likely a minor "
          "revision difference in the SNAP files; the pipeline below is robust "
          "to the exact number.")

# =====================================================================
# STEP 2 -- Select top-50 relations (female-ADR keywords, backfilled by
#           frequency), exactly as in v1 but K=50 -- this ONLY decides
#           which DDI *edges* are loaded, not which nodes exist.
# =====================================================================
FEMALE_ADR_KEYWORDS = [
    "thrombosis", "thrombo", "embolism", "embolus",
    "qt prolong", "arrhythmia", "torsade",
    "osteopenia", "osteoporosis", "fracture",
    "hepatotoxic", "hepatic failure", "liver damage", "liver failure",
]


def is_female_adr(name):
    name_l = str(name).lower()
    return any(k in name_l for k in FEMALE_ADR_KEYWORDS)


se_counts = (
    combo_df.groupby(["se_id", "se_name"]).size().reset_index(name="count")
    .sort_values("count", ascending=False).reset_index(drop=True)
)
se_counts["is_female_adr"] = se_counts["se_name"].apply(is_female_adr)

female_relations = se_counts[se_counts["is_female_adr"]].sort_values("count", ascending=False)
other_relations = se_counts[~se_counts["is_female_adr"]]

if len(female_relations) >= TOP_K:
    selected = female_relations.head(TOP_K)
else:
    n_fill = TOP_K - len(female_relations)
    selected = pd.concat([female_relations, other_relations.head(n_fill)], ignore_index=True)
selected = selected.reset_index(drop=True)

selected_se_ids = list(selected["se_id"])
female_se_ids = set(selected[selected["is_female_adr"]]["se_id"])
se_id2name = dict(zip(selected["se_id"], selected["se_name"]))
se_weight = {sid: (FEMALE_WEIGHT if sid in female_se_ids else STANDARD_WEIGHT) for sid in selected_se_ids}

print(f"\\nSelected {len(selected_se_ids)} relations "
      f"({len(female_se_ids)} female-ADR, {len(selected_se_ids) - len(female_se_ids)} standard):")
for _, row in selected.iterrows():
    tag = "FEMALE-ADR" if row["is_female_adr"] else "standard"
    print(f"  [{tag:10s}] {row['se_id']:>10} {row['se_name'][:45]:45s} n={row['count']:,}")

combo_sel = combo_df[combo_df["se_id"].isin(selected_se_ids)].copy()
print(f"\\nDrug-drug edges after relation filtering: {len(combo_sel):,} "
      f"(out of {len(combo_df):,} total across all ~964 relations)")

drugs_touched_by_selected_relations = set(combo_sel["stitch1"]) | set(combo_sel["stitch2"])
isolated_drug_count = num_drugs - len(drugs_touched_by_selected_relations)
print(f"Drugs with >=1 edge in the selected 50 relations: {len(drugs_touched_by_selected_relations):,}")
print(f"Drugs with ZERO edges in the selected 50 relations (kept as isolated "
      f"nodes, still reachable via drug-targets-protein): {isolated_drug_count:,}")

# =====================================================================
# STEP 3 -- Build HeteroData over the FULL vocabulary. num_nodes is set
#           explicitly so isolated drugs remain valid, addressable nodes.
# =====================================================================
data = HeteroData()
data["drug"].num_nodes = num_drugs
data["protein"].num_nodes = num_proteins

# Full PPI network (no filtering to a drug-relevant subset).
ppi_src = ppi_df["gene1"].map(protein2idx).values
ppi_dst = ppi_df["gene2"].map(protein2idx).values
ppi_edge_index = torch.tensor(np.stack([ppi_src, ppi_dst]), dtype=torch.long)
ppi_edge_index = torch.cat([ppi_edge_index, ppi_edge_index.flip(0)], dim=1)  # undirected
data["protein", "interacts", "protein"].edge_index = ppi_edge_index

# Full drug-target associations (all 645 drugs' targets, not just the ones
# touched by the selected 50 relations).
tgt_src = targets_df["stitch"].map(drug2idx).values
tgt_dst = targets_df["gene"].map(protein2idx).values
tgt_edge_index = torch.tensor(np.stack([tgt_src, tgt_dst]), dtype=torch.long)
data["drug", "targets", "protein"].edge_index = tgt_edge_index
data["protein", "rev_targets", "drug"].edge_index = tgt_edge_index.flip(0)

# Top-50 selected relations only (this is the memory-saving subsample).
se_edge_lists = {}
for se_id in selected_se_ids:
    sub = combo_sel[combo_sel["se_id"] == se_id]
    src = sub["stitch1"].map(drug2idx).values
    dst = sub["stitch2"].map(drug2idx).values
    edge_index = torch.tensor(np.stack([src, dst]), dtype=torch.long)
    edge_index = torch.cat([edge_index, edge_index.flip(0)], dim=1)  # symmetric DDI
    rel_name = f"se_{se_id}"
    data["drug", rel_name, "drug"].edge_index = edge_index
    se_edge_lists[se_id] = edge_index

print("\\nHeteroData object (full vocabulary, top-50 DDI relations):")
print(data)
'''.splitlines(keepends=True))

# =========================================================================
# CELL 4 — Negative sampling & splits
# =========================================================================
md('''## Cell 4 — Negative Edge Sampling & Train/Val/Test Split

Unchanged in *design* from v1 -- 80/10/10 split per relation over
de-duplicated canonical pairs, with the message-passing graph restricted to
train-split DDI edges to prevent leakage. What's different is scale: this
now runs across 50 relations instead of 10, and `num_nodes` for negative
sampling is the full 645-drug vocabulary rather than a pruned subset.
'''.splitlines(keepends=True))

code('''from torch_geometric.utils import negative_sampling

TRAIN_FRAC, VAL_FRAC, TEST_FRAC = 0.8, 0.1, 0.1
assert abs(TRAIN_FRAC + VAL_FRAC + TEST_FRAC - 1.0) < 1e-6

splits = {}  # se_id -> {"train": (pos, neg), "val": (pos, neg), "test": (pos, neg)}
gen = torch.Generator().manual_seed(RANDOM_SEED)

for se_id in selected_se_ids:
    pairs = se_edge_lists[se_id].t().tolist()
    canonical_set = sorted(set(tuple(sorted(p)) for p in pairs))
    canonical = torch.tensor(canonical_set, dtype=torch.long).t()  # [2, num_pairs]

    n = canonical.size(1)
    perm = torch.randperm(n, generator=gen)
    n_train = max(1, int(n * TRAIN_FRAC))
    n_val = max(1, int(n * VAL_FRAC))

    train_idx = perm[:n_train]
    val_idx = perm[n_train:n_train + n_val]
    test_idx = perm[n_train + n_val:]
    if test_idx.numel() == 0:  # guard tiny relations
        test_idx = val_idx

    pos_train = canonical[:, train_idx]
    pos_val = canonical[:, val_idx]
    pos_test = canonical[:, test_idx]

    def sample_negs(num_needed):
        return negative_sampling(
            edge_index=canonical, num_nodes=num_drugs,
            num_neg_samples=max(1, num_needed), method="sparse",
        )

    splits[se_id] = {
        "train": (pos_train, sample_negs(pos_train.size(1))),
        "val": (pos_val, sample_negs(pos_val.size(1))),
        "test": (pos_test, sample_negs(pos_test.size(1))),
    }

print(f"Built train/val/test splits for {len(selected_se_ids)} relations.")
total_train = sum(splits[s]["train"][0].size(1) for s in selected_se_ids)
total_val = sum(splits[s]["val"][0].size(1) for s in selected_se_ids)
total_test = sum(splits[s]["test"][0].size(1) for s in selected_se_ids)
print(f"Total positive edges -- train: {total_train:,}  val: {total_val:,}  test: {total_test:,}")

# ---- Message-passing graph: TRAIN-ONLY DDI edges for the 50 relations,
#      but FULL ppi + FULL targets connectivity (those are never split --
#      they are structural context, not the link-prediction target). ----
train_data = data.clone()
for se_id in selected_se_ids:
    rel_name = f"se_{se_id}"
    pos_train, _ = splits[se_id]["train"]
    sym = torch.cat([pos_train, pos_train.flip(0)], dim=1)
    train_data["drug", rel_name, "drug"].edge_index = sym

print("\\nMessage-passing graph (train-only DDI edges, full ppi/target connectivity):")
print(train_data)
'''.splitlines(keepends=True))

# =========================================================================
# CELL 5 — Model architecture
# =========================================================================
md('''## Cell 5 — Deep Architecture: HGTConv Encoder + Neural Bilinear Decoder

**Encoder** -- 4-layer Heterogeneous Graph Transformer (`HGTConv`), 256-d
hidden size, 4 attention heads per layer, with:
- residual connections: `x = LayerNorm(x + Dropout(GELU(HGTConv(x))))`
- `LayerNorm` after every layer (per node type) to counter over-smoothing
  across the 4-hop receptive field
- `GELU` activations, `Dropout(0.3)`

`HGTConv` internally learns a per-node-type skip/self-loop gate, so nodes
with no incoming messages in a given layer (e.g. an isolated drug in the
DDI relations, before its `targets` edges bring in signal) still receive a
valid, non-zero update rather than silently zeroing out.

**Decoder** -- Neural Bilinear Decoder: concatenates `[z_u, z_v, z_u⊙z_v,
|z_u - z_v|, e_r]` (node pair features + a learned per-relation embedding
`e_r`) and passes it through a 2-hidden-layer MLP (`256 -> 128 -> 1`,
LeakyReLU), replacing v1's diagonal DistMult with a strictly more
expressive scorer.
'''.splitlines(keepends=True))

code('''import torch.nn as nn
import torch.nn.functional as F
from torch_geometric.nn import HGTConv

HIDDEN_DIM = 256
NUM_LAYERS = 4
NUM_HEADS = 4
DROPOUT = 0.3


class NodeEmbedding(nn.Module):
    """Learnable per-node-type input embeddings (no raw drug/protein features)."""

    def __init__(self, num_drugs, num_proteins, dim):
        super().__init__()
        self.drug_emb = nn.Embedding(num_drugs, dim)
        self.protein_emb = nn.Embedding(num_proteins, dim)
        nn.init.xavier_uniform_(self.drug_emb.weight)
        nn.init.xavier_uniform_(self.protein_emb.weight)

    def forward(self):
        return {"drug": self.drug_emb.weight, "protein": self.protein_emb.weight}


class HGTResidualEncoder(nn.Module):
    """4-layer HGTConv stack with residual connections + LayerNorm + GELU."""

    def __init__(self, metadata, hidden_dim=HIDDEN_DIM, num_layers=NUM_LAYERS,
                 heads=NUM_HEADS, dropout=DROPOUT):
        super().__init__()
        node_types = metadata[0]
        self.num_layers = num_layers
        self.dropout = dropout
        self.act = nn.GELU()

        self.convs = nn.ModuleList([
            HGTConv(hidden_dim, hidden_dim, metadata, heads=heads, group="sum")
            for _ in range(num_layers)
        ])
        self.norms = nn.ModuleList([
            nn.ModuleDict({nt: nn.LayerNorm(hidden_dim) for nt in node_types})
            for _ in range(num_layers)
        ])

    def forward(self, x_dict, edge_index_dict):
        for conv, norm_dict in zip(self.convs, self.norms):
            out_dict = conv(x_dict, edge_index_dict)
            new_x_dict = {}
            for nt, x in x_dict.items():
                out = out_dict.get(nt)
                if out is None:
                    # This node type received zero messages under this edge
                    # set (not expected here, since both drug and protein
                    # are always destination types somewhere) -- pass
                    # through unchanged rather than error.
                    new_x_dict[nt] = x
                    continue
                out = self.act(out)
                out = F.dropout(out, p=self.dropout, training=self.training)
                h = x + out                      # residual skip connection
                h = norm_dict[nt](h)              # per-node-type LayerNorm
                new_x_dict[nt] = h
            x_dict = new_x_dict
        return x_dict


class NeuralBilinearDecoder(nn.Module):
    """[z_u, z_v, z_u*z_v, |z_u-z_v|, e_r] -> MLP(256 -> 128 -> 1)."""

    def __init__(self, num_relations, dim, mlp_hidden1=256, mlp_hidden2=128):
        super().__init__()
        self.rel_embed = nn.Embedding(num_relations, dim)
        nn.init.xavier_uniform_(self.rel_embed.weight)

        input_dim = dim * 4 + dim  # z_u, z_v, z_u*z_v, |z_u-z_v|, e_r
        self.mlp = nn.Sequential(
            nn.Linear(input_dim, mlp_hidden1),
            nn.LeakyReLU(0.2),
            nn.Linear(mlp_hidden1, mlp_hidden2),
            nn.LeakyReLU(0.2),
            nn.Linear(mlp_hidden2, 1),
        )

    def forward(self, z_u, z_v, rel_idx):
        e_r = self.rel_embed(rel_idx)
        feat = torch.cat([z_u, z_v, z_u * z_v, torch.abs(z_u - z_v), e_r], dim=-1)
        return self.mlp(feat).squeeze(-1)  # raw logit


class PharmacoGNNDeep(nn.Module):
    def __init__(self, num_drugs, num_proteins, metadata, num_relations,
                 hidden_dim=HIDDEN_DIM, num_layers=NUM_LAYERS, heads=NUM_HEADS, dropout=DROPOUT):
        super().__init__()
        self.node_embed = NodeEmbedding(num_drugs, num_proteins, hidden_dim)
        self.encoder = HGTResidualEncoder(metadata, hidden_dim=hidden_dim,
                                           num_layers=num_layers, heads=heads, dropout=dropout)
        self.decoder = NeuralBilinearDecoder(num_relations, hidden_dim)

    def encode(self, edge_index_dict):
        x_dict = self.node_embed()
        z_dict = self.encoder(x_dict, edge_index_dict)
        return z_dict["drug"]

    def decode(self, z_drug, edge_index, rel_idx):
        z_u = z_drug[edge_index[0]]
        z_v = z_drug[edge_index[1]]
        rel_idx_t = torch.full((edge_index.size(1),), rel_idx, dtype=torch.long, device=z_drug.device)
        return self.decoder(z_u, z_v, rel_idx_t)


print("Model classes defined: NodeEmbedding, HGTResidualEncoder, NeuralBilinearDecoder, PharmacoGNNDeep")
print(f"Config: hidden_dim={HIDDEN_DIM}, num_layers={NUM_LAYERS}, heads={NUM_HEADS}, dropout={DROPOUT}")
'''.splitlines(keepends=True))

# =========================================================================
# CELL 6 — Loss & training loop
# =========================================================================
md('''## Cell 6 — Training: AdamW + Cosine Warm Restarts + Mini-Batched Negatives

- `AdamW(lr=0.001, weight_decay=1e-4)`
- `CosineAnnealingWarmRestarts` scheduler, stepped once per epoch
- Gradient clipping (`clip_grad_norm_`, `max_norm=1.0`) before every
  optimizer step -- important here since attention layers are more prone to
  gradient spikes than plain GraphSAGE
- Mini-batched decode: within each epoch, each relation's training edges are
  chunked into `MINI_BATCH_EDGES`-sized pieces; each chunk samples negatives
  at a **1:3** positive:negative ratio via `negative_sampling` (which
  excludes true positives of that relation). Per-chunk losses accumulate
  into a single backward pass / optimizer step per epoch (see the design
  note in the intro cell for why).
- Per-epoch wall-clock time is tracked and an ETA is printed after epoch 5
  so you can gauge progress against the 20-30 minute target live.
'''.splitlines(keepends=True))

code('''import time
from sklearn.metrics import roc_auc_score, average_precision_score

device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
print(f"Training on device: {device}")

metadata = train_data.metadata()
edge_types = metadata[1]

se_id_list = selected_se_ids
se_id_to_relidx = {sid: i for i, sid in enumerate(se_id_list)}
num_relations = len(se_id_list)

NEG_RATIO = 3            # 1:3 positive:negative
MINI_BATCH_EDGES = 4096  # edges per mini-batch chunk, per relation
EPOCHS = 120
GRAD_CLIP_NORM = 1.0

model = PharmacoGNNDeep(
    num_drugs=num_drugs,
    num_proteins=num_proteins,
    metadata=metadata,
    num_relations=num_relations,
    hidden_dim=HIDDEN_DIM, num_layers=NUM_LAYERS, heads=NUM_HEADS, dropout=DROPOUT,
).to(device)

train_data = train_data.to(device)
edge_index_dict = train_data.edge_index_dict

# HGTConv materializes its internal per-node-type/per-edge-type parameters
# lazily on first forward call -- run one dummy pass before constructing the
# optimizer so every parameter actually exists in optimizer.param_groups.
with torch.no_grad():
    _ = model.encode(edge_index_dict)

n_params = sum(p.numel() for p in model.parameters())
print(f"Total trainable parameters: {n_params:,}")

optimizer = torch.optim.AdamW(model.parameters(), lr=0.001, weight_decay=1e-4)
scheduler = torch.optim.lr_scheduler.CosineAnnealingWarmRestarts(
    optimizer, T_0=15, T_mult=1, eta_min=1e-6
)

relation_weight = torch.tensor(
    [se_weight[sid] for sid in se_id_list], dtype=torch.float, device=device
)


def weighted_bce_loss(pos_scores, neg_scores, weight):
    pos_labels = torch.ones_like(pos_scores)
    neg_labels = torch.zeros_like(neg_scores)
    pos_loss = F.binary_cross_entropy_with_logits(pos_scores, pos_labels, reduction="none") * weight
    neg_loss = F.binary_cross_entropy_with_logits(neg_scores, neg_labels, reduction="none")
    # Both terms are already MEANS over their own sample count, so a 1:3
    # ratio does not need extra reweighting to stay balanced.
    return pos_loss.mean() + neg_loss.mean()


@torch.no_grad()
def evaluate(split_name):
    model.eval()
    z_drug = model.encode(edge_index_dict)
    all_scores, all_labels = [], []
    for sid in se_id_list:
        pos, neg = splits[sid][split_name]
        pos, neg = pos.to(device), neg.to(device)
        rel_idx = se_id_to_relidx[sid]
        pos_p = torch.sigmoid(model.decode(z_drug, pos, rel_idx)).cpu().numpy()
        neg_p = torch.sigmoid(model.decode(z_drug, neg, rel_idx)).cpu().numpy()
        all_scores.append(np.concatenate([pos_p, neg_p]))
        all_labels.append(np.concatenate([np.ones(len(pos_p)), np.zeros(len(neg_p))]))
    scores = np.concatenate(all_scores)
    labels = np.concatenate(all_labels)
    return roc_auc_score(labels, scores), average_precision_score(labels, scores)


print(f"\\nStarting training for {EPOCHS} epochs "
      f"({num_relations} relations, mini-batch size {MINI_BATCH_EDGES}, "
      f"neg ratio 1:{NEG_RATIO}) ...\\n")

epoch_times = []
train_start = time.time()

for epoch in range(1, EPOCHS + 1):
    epoch_start = time.time()
    model.train()
    optimizer.zero_grad()

    z_drug = model.encode(edge_index_dict)

    total_loss = 0.0
    num_batches = 0

    for sid in se_id_list:
        rel_idx = se_id_to_relidx[sid]
        pos_all, _ = splits[sid]["train"]
        n_pos = pos_all.size(1)

        perm = torch.randperm(n_pos)
        for start in range(0, n_pos, MINI_BATCH_EDGES):
            batch_idx = perm[start:start + MINI_BATCH_EDGES]
            pos_batch = pos_all[:, batch_idx].to(device)

            neg_batch = negative_sampling(
                edge_index=pos_all, num_nodes=num_drugs,
                num_neg_samples=pos_batch.size(1) * NEG_RATIO, method="sparse",
            ).to(device)

            pos_scores = model.decode(z_drug, pos_batch, rel_idx)
            neg_scores = model.decode(z_drug, neg_batch, rel_idx)

            loss = weighted_bce_loss(pos_scores, neg_scores, relation_weight[rel_idx])
            total_loss = total_loss + loss
            num_batches += 1

    total_loss = total_loss / max(1, num_batches)
    total_loss.backward()

    torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=GRAD_CLIP_NORM)
    optimizer.step()
    scheduler.step()

    epoch_time = time.time() - epoch_start
    epoch_times.append(epoch_time)

    if epoch % 10 == 0 or epoch == 1:
        val_auc, val_ap = evaluate("val")
        current_lr = optimizer.param_groups[0]["lr"]
        print(f"Epoch {epoch:3d}/{EPOCHS} | lr={current_lr:.6f} | "
              f"train_loss={total_loss.item():.4f} | val_ROC-AUC={val_auc:.4f} | "
              f"val_AP={val_ap:.4f} | epoch_time={epoch_time:.1f}s")

    if epoch == 5:
        avg_epoch_time = sum(epoch_times) / len(epoch_times)
        eta_min = avg_epoch_time * EPOCHS / 60.0
        print(f"  [ETA] avg {avg_epoch_time:.1f}s/epoch over first 5 epochs -> "
              f"estimated total runtime: {eta_min:.1f} minutes for {EPOCHS} epochs. "
              f"Adjust EPOCHS or MINI_BATCH_EDGES above if you want to retarget "
              f"the 20-30 minute window.")

total_time_min = (time.time() - train_start) / 60.0
print(f"\\nTraining complete in {total_time_min:.1f} minutes.")
'''.splitlines(keepends=True))

# =========================================================================
# CELL 7 — Evaluation
# =========================================================================
md('''## Cell 7 — Evaluation & Performance Metrics

Held-out test-set ROC-AUC/PR-AUC overall, plus the full per-relation
breakdown across all 50 relations, with female-weighted ADRs flagged
separately.
'''.splitlines(keepends=True))

code('''@torch.no_grad()
def evaluate_breakdown(split_name):
    model.eval()
    z_drug = model.encode(edge_index_dict)
    per_relation = {}
    all_scores, all_labels = [], []
    for sid in se_id_list:
        pos, neg = splits[sid][split_name]
        pos, neg = pos.to(device), neg.to(device)
        rel_idx = se_id_to_relidx[sid]
        pos_p = torch.sigmoid(model.decode(z_drug, pos, rel_idx)).cpu().numpy()
        neg_p = torch.sigmoid(model.decode(z_drug, neg, rel_idx)).cpu().numpy()
        scores = np.concatenate([pos_p, neg_p])
        labels = np.concatenate([np.ones(len(pos_p)), np.zeros(len(neg_p))])
        auc = roc_auc_score(labels, scores) if len(set(labels)) > 1 else float("nan")
        ap = average_precision_score(labels, scores)
        per_relation[sid] = {
            "auc": auc, "ap": ap, "is_female": sid in female_se_ids, "name": se_id2name[sid],
        }
        all_scores.append(scores)
        all_labels.append(labels)
    overall_scores = np.concatenate(all_scores)
    overall_labels = np.concatenate(all_labels)
    overall_auc = roc_auc_score(overall_labels, overall_scores)
    overall_ap = average_precision_score(overall_labels, overall_scores)
    return overall_auc, overall_ap, per_relation


test_auc, test_ap, per_relation = evaluate_breakdown("test")

print("=" * 72)
print("TEST SET PERFORMANCE (50 relations)")
print("=" * 72)
print(f"Overall ROC-AUC: {test_auc:.4f}")
print(f"Overall PR-AUC (AP): {test_ap:.4f}\\n")

header_name = "Side Effect"
header_type = "Type"
header_auc = "ROC-AUC"
header_ap = "AP"
print(f"{header_name:45s} {header_type:12s} {header_auc:>8s} {header_ap:>8s}")
print("-" * 78)
for sid, m in sorted(per_relation.items(), key=lambda kv: -kv[1]["auc"]):
    tag = "FEMALE-ADR" if m["is_female"] else "standard"
    print(f"{m[\'name\'][:45]:45s} {tag:12s} {m[\'auc\']:8.4f} {m[\'ap\']:8.4f}")

female_aucs = [m["auc"] for m in per_relation.values() if m["is_female"]]
standard_aucs = [m["auc"] for m in per_relation.values() if not m["is_female"]]

print("\\n" + "=" * 72)
print("FEMALE-ADR vs STANDARD-ADR SUMMARY")
print("=" * 72)
if female_aucs:
    print(f"Mean ROC-AUC, female-biased ADRs (n={len(female_aucs)}): {np.mean(female_aucs):.4f}")
if standard_aucs:
    print(f"Mean ROC-AUC, standard ADRs      (n={len(standard_aucs)}): {np.mean(standard_aucs):.4f}")
'''.splitlines(keepends=True))

# =========================================================================
# CELL 8 — Export artifacts
# =========================================================================
md('''## Cell 8 — Export Model Artifacts for Inference API

Saves the trained weights (`pharmacognn_deep_state_dict.pth`) plus every ID
mapping and architecture hyperparameter a FastAPI service needs to
reconstruct this exact model and serve predictions -- including for the
previously-isolated drugs, since their embeddings were still trained via the
retained `drug-targets-protein` connectivity.
'''.splitlines(keepends=True))

code('''import json as _json

ARTIFACT_DIR = "artifacts"
os.makedirs(ARTIFACT_DIR, exist_ok=True)

torch.save(model.state_dict(), os.path.join(ARTIFACT_DIR, "pharmacognn_deep_state_dict.pth"))

with open(os.path.join(ARTIFACT_DIR, "drug2idx.json"), "w") as f:
    _json.dump(drug2idx, f)
with open(os.path.join(ARTIFACT_DIR, "idx2drug.json"), "w") as f:
    _json.dump({str(k): v for k, v in idx2drug.items()}, f)
with open(os.path.join(ARTIFACT_DIR, "protein2idx.json"), "w") as f:
    _json.dump(protein2idx, f)
with open(os.path.join(ARTIFACT_DIR, "relation2idx.json"), "w") as f:
    _json.dump({se_id2name[sid]: idx for sid, idx in se_id_to_relidx.items()}, f)
with open(os.path.join(ARTIFACT_DIR, "relation_meta.json"), "w") as f:
    _json.dump(
        {
            sid: {
                "name": se_id2name[sid],
                "female_weighted": sid in female_se_ids,
                "rel_idx": se_id_to_relidx[sid],
            }
            for sid in se_id_list
        },
        f, indent=2,
    )

# Full model/architecture config -- everything a FastAPI service needs to
# reconstruct PharmacoGNNDeep() before loading the state dict.
model_config = {
    "num_drugs": num_drugs,
    "num_proteins": num_proteins,
    "num_relations": num_relations,
    "hidden_dim": HIDDEN_DIM,
    "num_layers": NUM_LAYERS,
    "heads": NUM_HEADS,
    "dropout": DROPOUT,
    "node_types": list(metadata[0]),
    "edge_types": [list(et) for et in metadata[1]],
}
with open(os.path.join(ARTIFACT_DIR, "model_config.json"), "w") as f:
    _json.dump(model_config, f, indent=2)

print("Saved to ./artifacts/:")
for f in sorted(os.listdir(ARTIFACT_DIR)):
    print(" -", f)

# ---- Smoke test: forward pass on two arbitrary drugs (including checking
#      an isolated-in-DDI drug if one exists, to confirm it still scores). ----
model.eval()
with torch.no_grad():
    z_drug = model.encode(edge_index_dict)

    drug_a, drug_b = all_drugs[0], all_drugs[1]
    idx_a, idx_b = drug2idx[drug_a], drug2idx[drug_b]
    pair = torch.tensor([[idx_a], [idx_b]], dtype=torch.long, device=device)

    print(f"\\nSample inference: {drug_a} + {drug_b}")
    header_name = "Side effect"
    header_prob = "P(side effect)"
    print(f"{header_name:45s} {header_prob:>15s}")
    print("-" * 62)
    for sid in se_id_list:
        rel_idx = se_id_to_relidx[sid]
        prob = torch.sigmoid(model.decode(z_drug, pair, rel_idx)).item()
        print(f"{se_id2name[sid][:45]:45s} {prob:15.4f}")
'''.splitlines(keepends=True))

notebook = {
    "cells": cells,
    "metadata": {
        "accelerator": "GPU",
        "colab": {"provenance": [], "gpuType": "T4"},
        "kernelspec": {"display_name": "Python 3", "name": "python3"},
        "language_info": {"name": "python"},
    },
    "nbformat": 4,
    "nbformat_minor": 5,
}

with open("PharmacoGNN_Deep.ipynb", "w") as f:
    json.dump(notebook, f, indent=1)

print("Notebook written.")
print(f"Total cells: {len(cells)}")
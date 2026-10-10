DROP TABLE IF EXISTS mitigation_events;
DROP TABLE IF EXISTS telemetry_logs;
DROP TABLE IF EXISTS nodes;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    email TEXT UNIQUE NOT NULL,
    pwd_hash TEXT NOT NULL,
    role TEXT NOT NULL
);

CREATE TABLE nodes (
    node_id TEXT PRIMARY KEY,
    first_seen TEXT,
    last_seen TEXT,
    fw_version TEXT
);

CREATE TABLE telemetry_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    node_id TEXT REFERENCES nodes(node_id),
    seq INTEGER,
    stime_us INTEGER,
    delta_t_ms REAL,
    dt_std_20 REAL,
    bytes INTEGER,
    proto TEXT,
    state TEXT,
    flgs TEXT,
    score REAL,
    is_attack INTEGER,
    infer_us INTEGER,
    http_status INTEGER,
    logged_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE mitigation_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    node_id TEXT REFERENCES nodes(node_id),
    started_at TEXT NOT NULL DEFAULT (datetime('now')),
    ended_at TEXT,
    vector TEXT NOT NULL,
    peak_score REAL NOT NULL,
    packets_blocked INTEGER NOT NULL DEFAULT 0,
    action TEXT NOT NULL DEFAULT 'HTTP_403'
);

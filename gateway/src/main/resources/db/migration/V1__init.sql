CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    pwd_hash TEXT NOT NULL,
    role TEXT NOT NULL
);

CREATE TABLE nodes (
    node_id TEXT PRIMARY KEY,
    first_seen TIMESTAMP,
    last_seen TIMESTAMP
);

CREATE TABLE telemetry_logs (
    id BIGSERIAL PRIMARY KEY,
    node_id TEXT REFERENCES nodes(node_id),
    seq BIGINT,
    stime_us BIGINT,
    proto TEXT,
    state TEXT,
    flgs TEXT,
    bytes INT,
    score REAL,
    verdict TEXT,
    received_at TIMESTAMP
);

CREATE TABLE mitigation_events (
    id BIGSERIAL PRIMARY KEY,
    node_id TEXT REFERENCES nodes(node_id),
    start_ts TIMESTAMP,
    end_ts TIMESTAMP,
    peak_score REAL,
    packets_blocked INT
);

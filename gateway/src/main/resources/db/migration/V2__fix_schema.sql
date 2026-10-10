DROP TABLE mitigation_events;

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

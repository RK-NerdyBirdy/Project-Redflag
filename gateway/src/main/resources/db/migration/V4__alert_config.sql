CREATE TABLE IF NOT EXISTS email_alert_config (
    id INTEGER PRIMARY KEY,
    smtp_host TEXT NOT NULL,
    smtp_port INTEGER NOT NULL,
    smtp_tls INTEGER NOT NULL DEFAULT 1,
    smtp_user TEXT,
    smtp_pass_enc TEXT,
    recipients TEXT,
    enabled INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS email_alert_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    node_id TEXT,
    sent_at TEXT DEFAULT CURRENT_TIMESTAMP,
    subject TEXT,
    body_preview TEXT,
    success INTEGER NOT NULL,
    error_msg TEXT
);

INSERT INTO email_alert_config (id, smtp_host, smtp_port, smtp_tls, smtp_user, smtp_pass_enc, recipients, enabled, updated_at)
SELECT 1, 'smtp.gmail.com', 587, 1, '', '', '', 0, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM email_alert_config WHERE id = 1);

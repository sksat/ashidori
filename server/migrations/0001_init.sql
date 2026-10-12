-- ashidori sync server: initial schema.
-- All timestamps are Unix epoch milliseconds (INTEGER).

CREATE TABLE devices (
  id           TEXT PRIMARY KEY,
  name         TEXT NOT NULL,
  -- SHA-256 (lowercase hex) of the device bearer token. The token itself is never stored.
  token_hash   TEXT NOT NULL UNIQUE,
  created_at   INTEGER NOT NULL,
  last_seen_at INTEGER,
  revoked_at   INTEGER
);

CREATE TABLE pairing_codes (
  -- SHA-256 (lowercase hex) of the normalized 8-character code.
  code_hash  TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  -- 'admin' or the id of the device that created the code.
  created_by TEXT NOT NULL,
  used_at    INTEGER,
  used_by    TEXT
);

CREATE INDEX pairing_codes_expires_at ON pairing_codes (expires_at);

CREATE TABLE batches (
  -- Global, monotonically increasing cursor (AUTOINCREMENT: never reused).
  cursor     INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id  TEXT NOT NULL REFERENCES devices (id),
  stream     TEXT NOT NULL,
  seq        INTEGER NOT NULL,
  size       INTEGER NOT NULL,
  sha256     TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE (device_id, stream, seq)
);

-- Fixed-window rate limit counters, keyed by "<bucket>:<client ip>".
CREATE TABLE rate_limits (
  key          TEXT PRIMARY KEY,
  window_start INTEGER NOT NULL,
  count        INTEGER NOT NULL
);

CREATE INDEX rate_limits_window_start ON rate_limits (window_start);

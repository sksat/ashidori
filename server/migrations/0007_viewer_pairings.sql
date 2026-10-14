-- Browser pairing v1 (docs/design/browser-pairing-v1.md): read-only viewer
-- devices (a browser that received the batch key from an app) and the
-- pairing attempts that create them (src/viewer-pairings.ts).
--
-- Backward compatible with the Worker of migration 0006, which keeps serving
-- for a while on this schema (the deploy applies migrations first, and a
-- rollback runs it again): every new column has a default, and nothing that
-- Worker reads or writes changes meaning.
--
-- A viewer's token_hash is stored as 'v:' || SHA-256(token) (hex). That
-- Worker looks devices up by the plain hash only, so it never authenticates
-- a viewer token: it cannot give a read-only browser the rights of a full
-- device, nor ignore a viewer's expiry. It does list viewers as devices, and
-- counts them towards USER_MAX_DEVICES, while it serves.

-- 'device': an app device (all rights). 'viewer': a browser, read-only,
-- with an expiry, created by a pairing attempt that the device `granted_by`
-- approved.
ALTER TABLE devices ADD COLUMN kind TEXT NOT NULL DEFAULT 'device' CHECK (kind IN ('device', 'viewer'));
-- By D1's clock; NULL for app devices (no expiry). Checked in the
-- statement that authenticates a token.
ALTER TABLE devices ADD COLUMN expires_at INTEGER;
-- The app device that approved a viewer. Revoking that device revokes its
-- viewers in the same transaction.
ALTER TABLE devices ADD COLUMN granted_by TEXT;
CREATE INDEX devices_granted_by ON devices (granted_by) WHERE granted_by IS NOT NULL;
-- The 5-viewer cap and the scheduled purge of ended viewers.
CREATE INDEX devices_viewers ON devices (user_id, expires_at) WHERE kind = 'viewer';

CREATE TABLE viewer_pairings (
  -- 16 random bytes, base64url (22 characters).
  id            TEXT PRIMARY KEY,
  -- SHA-256 (hex) of the 8-character code. The code is shown by the
  -- browser; the server needs only to find the attempt by it.
  code_hash     TEXT NOT NULL UNIQUE,
  -- SHA-256 (hex) of the browser's token T. Unique across attempts; the
  -- server also refuses one that a device has (src/viewer-pairings.ts).
  token_hash    TEXT NOT NULL UNIQUE,
  -- c = H("ashidori pair commit v1" ‖ pkB ‖ nB), base64url.
  commit_b64    TEXT NOT NULL,
  state         TEXT NOT NULL DEFAULT 'open'
                  CHECK (state IN ('open', 'claimed', 'nonce', 'revealed', 'active', 'aborted')),
  -- Both by D1's clock. Every transition towards 'active' checks
  -- D1's clock < expires_at in its own statement.
  created_at    INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL,
  -- Set by claim: the claiming app device and its user.
  claimed_by    TEXT,
  user_id       TEXT,
  -- Set by nonce (base64url, "batch-v1", 3600 or 86400).
  n_a           TEXT,
  scope         TEXT,
  ttl           INTEGER,
  -- Set by reveal (base64url).
  pk_b          TEXT,
  n_b           TEXT,
  -- Set by approve: the HPKE grant (base64url, 113 bytes), stored once.
  grant_b64     TEXT,
  approved_at   INTEGER,
  -- Set by confirm (the browser's 「一致する」).
  confirmed_at  INTEGER,
  -- Set with state 'active': the viewer device created.
  device_id     TEXT,
  activated_at  INTEGER,
  aborted_at    INTEGER
);

CREATE INDEX viewer_pairings_expires_at ON viewer_pairings (expires_at);
CREATE INDEX viewer_pairings_claimed_by ON viewer_pairings (claimed_by) WHERE claimed_by IS NOT NULL;

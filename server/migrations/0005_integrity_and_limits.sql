-- Storage integrity, usage counters and limits, safer pairing, revisions of
-- shared check-ins, and cheaper rate-limit counters.
--
-- Backward compatible with the Worker of migration 0003: the deploy workflow
-- applies migrations before it deploys, so that Worker keeps serving for a
-- while on this schema (and again after a rollback). Every new NOT NULL
-- column therefore has a default, and the triggers below fill in what that
-- Worker does not write (the issuer of a pairing code, the expiry of a
-- rate-limit counter).

-- ---- Batches: immutable R2 keys ----
-- New blobs are stored under a key of their own,
-- "b/{deviceId}/{stream}/{seq 12 digits}/{sha256}.{random}", recorded here
-- when the metadata is committed. Concurrent uploads to one slot thus never
-- overwrite each other's object; the row that wins the slot references the
-- winner's object. NULL: a batch stored before this migration, under the old
-- key "b/{deviceId}/{stream}/{seq 12 digits}" (not migrated; GET falls back
-- to it).
ALTER TABLE batches ADD COLUMN r2_key TEXT;
CREATE UNIQUE INDEX batches_r2_key ON batches (r2_key);

-- The quota no longer sums sizes (see the counters below).
DROP INDEX batches_device_size;

-- ---- Usage counters ----
-- Kept by the triggers below in the same statement as the change they count,
-- so they are exact. The quota checks read them instead of summing rows.
ALTER TABLE users ADD COLUMN storage_bytes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN batch_count INTEGER NOT NULL DEFAULT 0;
-- Shared check-ins, tombstones included.
ALTER TABLE users ADD COLUMN checkin_count INTEGER NOT NULL DEFAULT 0;

-- Exactly one row: server-wide counters. The legacy devices (user_id NULL)
-- share one quota, so their usage is kept here rather than in a user row.
CREATE TABLE server_usage (
  id                   INTEGER PRIMARY KEY CHECK (id = 1),
  user_count           INTEGER NOT NULL,
  total_bytes          INTEGER NOT NULL,
  legacy_storage_bytes INTEGER NOT NULL,
  legacy_batch_count   INTEGER NOT NULL,
  -- Where the scheduled sweep of unreferenced R2 objects continues (last key seen).
  orphan_sweep_after   TEXT
);

INSERT INTO server_usage (id, user_count, total_bytes, legacy_storage_bytes, legacy_batch_count)
SELECT 1,
  (SELECT COUNT(*) FROM users),
  (SELECT COALESCE(SUM(size), 0) FROM batches),
  (SELECT COALESCE(SUM(b.size), 0) FROM batches b JOIN devices d ON d.id = b.device_id WHERE d.user_id IS NULL),
  (SELECT COUNT(*) FROM batches b JOIN devices d ON d.id = b.device_id WHERE d.user_id IS NULL);

UPDATE users SET
  storage_bytes = (SELECT COALESCE(SUM(b.size), 0) FROM batches b JOIN devices d ON d.id = b.device_id WHERE d.user_id = users.id),
  batch_count = (SELECT COUNT(*) FROM batches b JOIN devices d ON d.id = b.device_id WHERE d.user_id = users.id),
  checkin_count = (SELECT COUNT(*) FROM shared_checkins p WHERE p.user_id = users.id);

CREATE TRIGGER batches_usage_insert AFTER INSERT ON batches BEGIN
  UPDATE users SET storage_bytes = storage_bytes + NEW.size, batch_count = batch_count + 1
    WHERE id = (SELECT user_id FROM devices WHERE id = NEW.device_id);
  UPDATE server_usage SET
    total_bytes = total_bytes + NEW.size,
    legacy_storage_bytes = legacy_storage_bytes
      + CASE WHEN (SELECT user_id FROM devices WHERE id = NEW.device_id) IS NULL THEN NEW.size ELSE 0 END,
    legacy_batch_count = legacy_batch_count
      + CASE WHEN (SELECT user_id FROM devices WHERE id = NEW.device_id) IS NULL THEN 1 ELSE 0 END
    WHERE id = 1;
END;

CREATE TRIGGER batches_usage_delete AFTER DELETE ON batches BEGIN
  UPDATE users SET storage_bytes = storage_bytes - OLD.size, batch_count = batch_count - 1
    WHERE id = (SELECT user_id FROM devices WHERE id = OLD.device_id);
  UPDATE server_usage SET
    total_bytes = total_bytes - OLD.size,
    legacy_storage_bytes = legacy_storage_bytes
      - CASE WHEN (SELECT user_id FROM devices WHERE id = OLD.device_id) IS NULL THEN OLD.size ELSE 0 END,
    legacy_batch_count = legacy_batch_count
      - CASE WHEN (SELECT user_id FROM devices WHERE id = OLD.device_id) IS NULL THEN 1 ELSE 0 END
    WHERE id = 1;
END;

CREATE TRIGGER users_count_insert AFTER INSERT ON users BEGIN
  UPDATE server_usage SET user_count = user_count + 1 WHERE id = 1;
END;

CREATE TRIGGER users_count_delete AFTER DELETE ON users BEGIN
  UPDATE server_usage SET user_count = user_count - 1 WHERE id = 1;
END;

CREATE TRIGGER checkins_count_insert AFTER INSERT ON shared_checkins BEGIN
  UPDATE users SET checkin_count = checkin_count + 1 WHERE id = NEW.user_id;
END;

CREATE TRIGGER checkins_count_delete AFTER DELETE ON shared_checkins BEGIN
  UPDATE users SET checkin_count = checkin_count - 1 WHERE id = OLD.user_id;
END;

-- The scheduled sweep of self-registered users that never stored anything.
CREATE INDEX users_self_unused ON users (created_at)
  WHERE created_by = 'self' AND batch_count = 0 AND checkin_count = 0;

-- ---- Pairing codes: the issuing device ----
-- NULL for codes issued by an admin (created_by 'admin' or 'admin:<email>').
-- A code is accepted only while its issuing device is not revoked, and
-- revoking a device deletes its unused codes.
ALTER TABLE pairing_codes ADD COLUMN issuer_device_id TEXT;
UPDATE pairing_codes SET issuer_device_id = created_by WHERE created_by IN (SELECT id FROM devices);
CREATE INDEX pairing_codes_issuer ON pairing_codes (issuer_device_id);
-- The older Worker inserts codes without issuer_device_id; its device-issued
-- codes have the device id in created_by. The server also derives the issuer
-- from created_by when issuer_device_id is NULL (src/devices.ts), so a NULL
-- left by any other path is not taken for an admin-issued code either.
CREATE TRIGGER pairing_codes_issuer_fill AFTER INSERT ON pairing_codes
  WHEN NEW.issuer_device_id IS NULL AND NEW.created_by <> 'admin' AND NEW.created_by NOT LIKE 'admin:%'
BEGIN
  UPDATE pairing_codes SET issuer_device_id = NEW.created_by WHERE code_hash = NEW.code_hash;
END;

-- ---- Shared check-ins: revisions ----
-- rev: the client's revision of the entry (e.g. its last change time in ms),
-- that of the last PUT or DELETE applied, tombstones included. A PUT or a
-- DELETE applies only if its rev is above the stored one (src/checkins.ts),
-- so a delayed or replayed older operation, PUT or DELETE, changes nothing.
-- Existing tombstones get the end of the hour they were deleted in.
ALTER TABLE shared_checkins ADD COLUMN rev INTEGER NOT NULL DEFAULT 0;
UPDATE shared_checkins SET rev = deleted_at + 3599999 WHERE deleted_at IS NOT NULL;
-- 1: removed by an admin. Such an entry stays removed: the owner cannot
-- share it again under the same local id (sharing it anew creates a new id).
ALTER TABLE shared_checkins ADD COLUMN removed_by_admin INTEGER NOT NULL DEFAULT 0
  CHECK (removed_by_admin IN (0, 1));

-- ---- Rate limits ----
-- Counters are transient (keyed by client IP), so the table is recreated
-- with an indexed expiry for the bounded scheduled cleanup.
DROP TABLE rate_limits;
CREATE TABLE rate_limits (
  key          TEXT PRIMARY KEY,
  window_start INTEGER NOT NULL,
  -- window_start + the window length of the counter's rule. 0 (the default)
  -- only while a trigger below replaces it.
  expires_at   INTEGER NOT NULL DEFAULT 0,
  count        INTEGER NOT NULL
);
CREATE INDEX rate_limits_expires_at ON rate_limits (expires_at);
-- The older Worker writes counters without expires_at (insert), or moves
-- window_start to a new window without it (update). Such a counter expires
-- one hour (the longest window of any rule) after its window started, so the
-- cleanup never deletes it while its window lasts. The current Worker always
-- sets expires_at above window_start, so these never fire for it.
CREATE TRIGGER rate_limits_expiry_insert AFTER INSERT ON rate_limits
  WHEN NEW.expires_at <= NEW.window_start
BEGIN
  UPDATE rate_limits SET expires_at = NEW.window_start + 3600000 WHERE key = NEW.key;
END;
CREATE TRIGGER rate_limits_expiry_update AFTER UPDATE OF window_start ON rate_limits
  WHEN NEW.expires_at <= NEW.window_start
BEGIN
  UPDATE rate_limits SET expires_at = NEW.window_start + 3600000 WHERE key = NEW.key;
END;

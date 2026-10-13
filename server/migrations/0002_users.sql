-- Multi-user: accounts created by admins. Users never sign in to the web; they
-- only use the app, whose devices belong to exactly one user.

CREATE TABLE users (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  -- Optional, for the admin's reference only (not used for authentication).
  email       TEXT,
  status      TEXT NOT NULL CHECK (status IN ('active', 'disabled')),
  created_at  INTEGER NOT NULL,
  -- E-mail address of the admin who created the user.
  created_by  TEXT NOT NULL,
  disabled_at INTEGER
);

-- NULL for devices registered before this migration (single-tenant era).
-- Such legacy devices only see each other.
ALTER TABLE devices ADD COLUMN user_id TEXT REFERENCES users (id);
CREATE INDEX devices_user_id ON devices (user_id);

-- The user the registered device will belong to; NULL only for legacy codes.
ALTER TABLE pairing_codes ADD COLUMN user_id TEXT;

-- Per-user batch listings filter by device; this lets them walk each device's
-- cursor range instead of scanning every user's batches.
CREATE INDEX batches_device_cursor ON batches (device_id, cursor);

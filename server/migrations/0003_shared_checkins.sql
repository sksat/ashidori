-- Shared check-ins and follows.
--
-- A user may share selected check-ins with the users who follow them. Unlike
-- everything else, shared check-ins are plaintext on the server: the user
-- chose to show them to others. Unsharing keeps a tombstone (content nulled
-- out) so that clients syncing incrementally learn about it.

CREATE TABLE shared_checkins (
  -- Server-generated (UUID).
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES users (id),
  device_id   TEXT NOT NULL REFERENCES devices (id),
  -- The check-in's id on the sharing device.
  local_id    TEXT NOT NULL,
  -- Check-in time (ms). The content columns are all NULL in tombstones.
  t           INTEGER,
  lat         REAL,
  lon         REAL,
  venue       TEXT,
  category    TEXT,
  place       TEXT,
  comment     TEXT,
  -- Who may read it. Only 'followers' for now; sharing with selected users
  -- (encrypted per recipient) is expected to add values.
  visibility  TEXT NOT NULL CHECK (visibility IN ('followers')),
  -- Rounded down to the hour.
  updated_at  INTEGER NOT NULL,
  deleted_at  INTEGER,
  -- Set to MAX(cursor) + 1 on every change (including the owner being
  -- disabled or enabled), so incremental listings see each change once.
  -- Rows are never deleted, so MAX(cursor) never decreases.
  cursor      INTEGER NOT NULL UNIQUE,
  UNIQUE (device_id, local_id),
  CHECK (deleted_at IS NOT NULL OR (t IS NOT NULL AND lat IS NOT NULL AND lon IS NOT NULL AND venue IS NOT NULL)),
  -- A tombstone keeps none of the unshared content.
  CHECK (deleted_at IS NULL OR COALESCE(t, lat, lon, venue, category, place, comment) IS NULL)
);

-- Moving all of a user's entries when the user is disabled or enabled.
CREATE INDEX shared_checkins_user_id ON shared_checkins (user_id);

-- The name other users look a user up by (exact match only, so users cannot
-- be enumerated). Set by an admin; lowercase. NULL: cannot be looked up.
ALTER TABLE users ADD COLUMN handle TEXT
  CHECK (handle IS NULL OR (handle = lower(handle) AND length(handle) BETWEEN 3 AND 20));
CREATE UNIQUE INDEX users_handle ON users (handle);

-- One-way: once the followee accepts, the follower sees the followee's shared
-- check-ins. Declining deletes the request.
CREATE TABLE follows (
  follower_id TEXT NOT NULL REFERENCES users (id),
  followee_id TEXT NOT NULL REFERENCES users (id),
  status      TEXT NOT NULL CHECK (status IN ('pending', 'accepted')),
  -- Both rounded down to the hour.
  created_at  INTEGER NOT NULL,
  accepted_at INTEGER,
  PRIMARY KEY (follower_id, followee_id),
  CHECK (follower_id <> followee_id),
  CHECK ((status = 'accepted') = (accepted_at IS NOT NULL))
);

CREATE INDEX follows_followee_id ON follows (followee_id, status);

-- Changes whenever the set of users a user follows (accepted) changes. Feed
-- responses carry it; a client that sees a new value resyncs its feed from
-- the start, because entries of added or removed followees are not in its
-- increments.
ALTER TABLE users ADD COLUMN follows_version INTEGER NOT NULL DEFAULT 0;

CREATE TRIGGER follows_insert_version AFTER INSERT ON follows WHEN NEW.status = 'accepted' BEGIN
  UPDATE users SET follows_version = follows_version + 1 WHERE id = NEW.follower_id;
END;

CREATE TRIGGER follows_update_version AFTER UPDATE OF status ON follows WHEN NEW.status <> OLD.status BEGIN
  UPDATE users SET follows_version = follows_version + 1 WHERE id = NEW.follower_id;
END;

CREATE TRIGGER follows_delete_version AFTER DELETE ON follows WHEN OLD.status = 'accepted' BEGIN
  UPDATE users SET follows_version = follows_version + 1 WHERE id = OLD.follower_id;
END;

-- Metadata minimization: from now on, last-seen and upload times are stored
-- rounded down to the hour. Coarsen what was stored before.
UPDATE devices SET last_seen_at = last_seen_at - (last_seen_at % 3600000) WHERE last_seen_at IS NOT NULL;
UPDATE batches SET created_at = created_at - (created_at % 3600000);

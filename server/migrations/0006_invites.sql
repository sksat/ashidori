-- Invitations and usernames.
--
-- An admin no longer creates users. The admin creates an invite: a
-- single-use registration link with an expiry. The person opens it on their
-- first app device, chooses a username there, and the user is created then,
-- together with that device (POST /v1/accounts with `invite`, src/invites.ts).
--
-- Backward compatible with the Worker of migration 0005, which keeps serving
-- for a while on this schema (the deploy applies migrations first, and a
-- rollback runs it again): this migration only adds a table that Worker does
-- not know.
--
-- Usernames: the column users.handle (migration 0003) is the username:
-- lowercase, 3-20 characters, unique (index users_handle). The API calls it
-- `username`; the column keeps its name, since renaming it would break that
-- Worker. NULL only for users created before this migration (legacy); the
-- admin page lists them as needing a username, and an admin sets it.

CREATE TABLE invites (
  id           TEXT PRIMARY KEY,
  -- SHA-256 (lowercase hex) of the 16-character code. The code itself is
  -- shown once, in the link, and never stored.
  code_hash    TEXT NOT NULL UNIQUE,
  -- "admin:<email>" of the admin who created it.
  created_by   TEXT NOT NULL,
  -- Both by D1's clock, the clock that registration judges the expiry by.
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  -- The user created with it; set in the same transaction that creates the
  -- user and its first device. One invite yields at most one user.
  used_by_user TEXT UNIQUE,
  used_at      INTEGER,
  -- Set only while unused. A revoked invite is never consumed.
  revoked_at   INTEGER,
  CHECK ((used_by_user IS NULL) = (used_at IS NULL)),
  CHECK (used_by_user IS NULL OR revoked_at IS NULL)
);

CREATE INDEX invites_created_at ON invites (created_at);

-- Self-registration and per-user storage quota (see src/accounts.ts).
--
-- users.created_by is now either the e-mail address of the admin who created
-- the user, or 'self' for a user created by its first device
-- (POST /v1/accounts, only when REGISTRATION = "open"). No column changes.

-- The quota check sums the blob sizes of a user's devices on every upload;
-- this covering index answers it from the index alone.
CREATE INDEX batches_device_size ON batches (device_id, size);

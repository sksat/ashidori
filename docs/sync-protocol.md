# ashidori sync protocol (v1)

ashidori works fully offline. Sync is optional: when a server is configured, each
device uploads its history so that it is backed up and visible from the
user's other devices.

Location history is sensitive, so the server stores the history only as
ciphertext. The server operator, Cloudflare, or anyone with access to the
account cannot read positions, check-ins or place names. The one exception is
check-ins that a user explicitly shares with their followers; see
[Shared check-ins and follows](#shared-check-ins-and-follows) and
[What the server operator can see](#what-the-server-operator-can-see).

## Deployment model

- One deployment serves several users. Every device belongs to exactly one
  user, and a user's devices share that user's data. Users do not sign in to
  the web; an admin creates the user and hands them a pairing code.
- Data is isolated per user: a device only sees the devices and batches of its
  own user. Devices registered before users existed have no user; they only
  see each other, and cannot use shared check-ins or follows.
- Shared check-ins are the only data that crosses users: a user sees the
  check-ins shared by the users they follow (after those users accepted).
- Runtime: Cloudflare Workers. D1 stores users, devices, batch metadata,
  follows and shared check-ins. R2
  stores the encrypted batch blobs.
- Admin routes (`/admin/*`) are for operators. They are protected by Cloudflare
  Access, and the Worker also verifies the `Cf-Access-Jwt-Assertion` JWT
  (audience `CF_ACCESS_AUD`, issuer `https://<CF_ACCESS_TEAM_DOMAIN>`) and
  requires its `email` to be listed in `ADMIN_EMAILS`, so a misconfigured
  route or a broad Access policy does not expose them.

## Keys and encryption

- **Account master key `K`**: 32 random bytes, generated on the first device.
  - It never leaves the user's devices in plaintext, and only the app uses it.
    A web page served by the sync server must never handle `K` (or the
    recovery code): the operator controls that page and could change it to
    send the key home. See [What the server operator can see](#what-the-server-operator-can-see).
  - It is shown once as a **recovery code**: Crockford base32 of `K` plus a 2-byte checksum, in groups of 4.
  - New devices receive it through a QR code shown on an existing device.
  - On Android it is stored wrapped by a non-exportable Android Keystore AES key.
- **Batch key** = HKDF-SHA256(ikm=`K`, salt=empty, info=`"ashidori batch v1"`), 32 bytes.
- **Blob format**: `"ASH1"` (4 bytes) ‖ nonce (12 random bytes) ‖ AES-256-GCM(ciphertext ‖ 16-byte tag).
  - The AAD is the UTF-8 string `"{deviceId}/{stream}/{seq}"`. A server that swaps or replays blobs between slots makes decryption fail.
- **Plaintext**: gzip of UTF-8 JSON Lines, one record per line. The record schema per stream is listed below.
- Of a batch, the server sees only: device id, stream name, sequence number,
  blob size, blob SHA-256 and the upload time rounded down to the hour. Time
  ranges of the data are inside the ciphertext.

## Authentication

- Each device has a **device token**: 32 random bytes, base64url, sent as
  `Authorization: Bearer <token>`.
  - The server stores only `SHA-256(token)` (hex).
  - The token is compared in constant time against the stored hash.
- **Pairing**:
  - A pairing code is 8 characters of Crockford base32, single use, with a TTL of 10 minutes.
  - Every code is bound to a user, and the device registered with it belongs to that user.
  - Codes are created either by an existing device (`POST /v1/pairing`, for the device's own user) or by an admin for a given user (`POST /admin/users/:id/pairing`).
  - A user's first device is always paired with a code from an admin.
- Tokens can be revoked per device. A revoked or unknown token gets `401`.
- An admin can disable a user. The user's device tokens then get `401` and the
  user's unused pairing codes are rejected, until the user is enabled again.
- Pairing, registration and handle lookups are rate limited per IP. The
  counters are deleted as soon as their window (10 minutes) has ended.

## HTTP API

All bodies are JSON unless noted. Errors are `{"error": "..."}` with a suitable status.

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/admin/users` | admin | list users |
| POST | `/admin/users` | admin | `{name, handle?, email?}` → the created user; `409` if the handle is taken |
| PATCH | `/admin/users/:id` | admin | `{name?, handle?}` (`handle: null` removes it) → the user |
| POST | `/admin/users/:id/pairing` | admin | → `{code, expiresAt}` for that user |
| POST | `/admin/users/:id/disable`, `/admin/users/:id/enable` | admin | disable / enable a user |
| GET | `/admin/devices` | admin | list all devices with their user |
| POST | `/admin/devices/:id/revoke` | admin | revoke any device |
| GET | `/admin/checkins` | admin | the 200 most recently changed shared check-ins (moderation) |
| POST | `/admin/checkins/:id/delete` | admin | remove a shared check-in (leaves a tombstone) |
| POST | `/v1/devices/register` | – | `{code, name}` → `{deviceId, token}`; the code is consumed |
| POST | `/v1/pairing` | device | → `{code, expiresAt}` for the device's user |
| GET | `/v1/devices` | device | the user's devices: `[{id, name, createdAt, lastSeenAt, revoked}]` |
| POST | `/v1/devices/:id/revoke` | device | revoke one of the user's devices (including self) |
| PUT | `/v1/batches/:stream/:seq` | device | body: blob (`application/octet-stream`, ≤ 8 MiB) |
| GET | `/v1/batches?after=<cursor>&limit=<n>` | device | metadata of the batches of all the user's devices, ordered by a global cursor |
| GET | `/v1/batches/:deviceId/:stream/:seq` | device | blob of one of the user's devices |
| PUT | `/v1/checkins/shared/:localId` | user device | share or update a check-in (see below) → `{id, cursor}` |
| DELETE | `/v1/checkins/shared/:localId` | user device | unshare it |
| GET | `/v1/checkins/feed?after=<cursor>&limit=<n>` | user device | own and followed users' shared check-ins |
| GET | `/v1/users/lookup?handle=<handle>` | user device | → `{id, handle, name}` of the active user with exactly that handle, else `404` |
| POST | `/v1/follows` | user device | `{userId}` → `{user: {id, handle, name}, status}`; sends a follow request |
| GET | `/v1/follows` | user device | users I follow or asked to: `[{id, handle, name, status, since}]` |
| DELETE | `/v1/follows/:userId` | user device | unfollow, or cancel a request |
| GET | `/v1/follow-requests` | user device | incoming requests: `[{id, handle, name, since}]` |
| POST | `/v1/follow-requests/:userId/accept` | user device | accept a request |
| POST | `/v1/follow-requests/:userId/decline` | user device | decline a request (deletes it) |
| GET | `/v1/followers` | user device | accepted followers: `[{id, handle, name, since}]` |
| DELETE | `/v1/followers/:userId` | user device | remove a follower |
| GET | `/v1/health` | – | `{ok: true}` |

- `stream` matches `^[a-z][a-z0-9_]{0,31}$`. `seq` is a non-negative integer that the device assigns per stream, counting up from 0.
- `PUT` is idempotent. If the same `(device, stream, seq)` already exists with the same SHA-256, the server returns `200 {"status":"exists"}`. If it exists with a different hash, the server returns `409`. A new upload returns `201`.
  - The device id comes from the token, never from the URL.
  - The server records `X-Content-SHA256` (hex, required) and verifies it against the body.
- The list response is `{items: [{cursor, deviceId, stream, seq, size, sha256, createdAt}], next}`. `next` is null at the end.
  - The cursor is shared by all users, so a user's listing skips values. Clients only rely on it increasing.
- Listings, fetches and revocations only cover the caller's user. A device of another user is reported like a missing one (`404`).
- R2 key: `b/{deviceId}/{stream}/{seq as 12-digit zero padded}`.
- "user device" means a device that belongs to a user. Legacy devices without a user get `403 {"error": "no user"}`.

## Shared check-ins and follows

A user can share selected check-ins with their followers. Shared check-ins are
**plaintext on the server**, by definition: the user chose to show them to
other people. Everything else stays end-to-end encrypted in batches.

### Follows

- Each user may have a **handle** (`^[a-z0-9_]{3,20}$`, unique, case-insensitive, stored lowercase), set by an admin. A user without a handle cannot be looked up.
- There is no user directory and no search. `GET /v1/users/lookup` matches the exact handle only and is rate limited per IP, so users cannot be enumerated.
- `POST /v1/follows {userId}` sends a follow request (`pending`). It is idempotent and returns the current status. Following yourself is refused (`400`); an unknown or disabled user is `404`.
- The followee accepts (`accepted`) or declines the request. Declining deletes it, so the request can be sent again.
- Following is one-way. The follower can unfollow (or cancel a request); the followee can remove a follower.
- Disabled users cannot be looked up or followed, and do not appear in follow lists.
- `since` is when the follow was accepted (for requests: when it was sent), rounded down to the hour.

### Sharing

- `PUT /v1/checkins/shared/:localId` with `{t, lat, lon, venue, category?, place?, comment?}`.
  - `localId` is the device-local check-in id and matches `^[A-Za-z0-9_-]{1,64}$`. It is scoped to the device: the same id from another device is another entry.
  - `t`: check-in time, non-negative integer (ms). `lat` in [-90, 90], `lon` in [-180, 180].
  - `venue`: 1–200 characters after trimming. `category`, `place`: ≤ 200 characters. `comment`: ≤ 1000 characters; tabs and line breaks are allowed. Other control characters are rejected. Empty optional fields are stored as null.
  - The body is at most 4 KiB.
  - The server assigns the entry an `id`. Sharing the same `localId` again updates the entry (and reshares it if it was unshared). An unchanged re-upload is a no-op.
- `DELETE /v1/checkins/shared/:localId` unshares. The server keeps a tombstone and deletes the content (time, position, venue, category, place, comment). Unsharing twice is a no-op; an unknown id is `404`.
- Every entry has `visibility`. It is always `followers` for now. Sharing with selected users, encrypted per recipient with public keys, is expected to add other values without changing the API above.

### Feed

`GET /v1/checkins/feed?after=<cursor>&limit=<n ≤ 500>` returns the caller's own
entries and those of the users the caller follows (accepted), ordered by cursor:

```json
{
  "items": [{"id": "...", "cursor": 12, "user": {"id": "...", "handle": "...", "name": "..."},
             "t": 0, "lat": 0, "lon": 0, "venue": "...", "category": null, "place": null,
             "comment": null, "updatedAt": 0, "deleted": false}],
  "next": null,
  "followsVersion": 3
}
```

- Every change to an entry moves it to a new, higher cursor: sharing, editing, unsharing, removal by an admin, and its owner being disabled or enabled. A client syncing with `after=<last cursor>` therefore sees each change once.
- `deleted: true` means unshared, removed by an admin, or the owner is disabled. The content fields are then null. When a disabled user is enabled again, the entries reappear with new cursors.
- `updatedAt` is rounded down to the hour.
- `followsVersion` changes whenever the set of users the caller follows changes (a request is accepted, the caller unfollows, or a followee removes the caller). Entries of a newly followed user are older than the client's cursor, and entries of a no longer followed user produce no change, so increments cannot express this. **When `followsVersion` differs from the value stored with the cursor, the client discards its feed and syncs again from `after=0`.** It checks this on every page.

## What the server operator can see

The threat model: the operator of the sync server (whoever controls the
Cloudflare account and the admin page) is not trusted with location history.
The design keeps the operator from reading it; it does not hide that a user
uses the service.

- **Detailed logs**: ciphertext only. Without `K` the operator cannot read positions, times, places or check-ins in batches.
- **Metadata the server keeps**:
  - users: name, handle, optional e-mail address, status, creation time;
  - devices: id, name, registration time, last-seen time (rounded down to the hour);
  - batches: device, stream name, sequence number, blob size, SHA-256, upload time (rounded down to the hour);
  - follows: who follows whom, with request and acceptance times (rounded down to the hour);
  - shared check-ins: **in plaintext**, as shared, with the hour of the last change. Unshared entries keep only a tombstone.
- **Residual leaks**:
  - The number and size of batches per stream and hour hint at activity, e.g. that the user was moving (more `fixes`), or that a device was offline.
  - Stream names tell which kinds of data a user records (e.g. raw GNSS logs).
  - Cloudflare sees client IP addresses and request times at its edge. The Worker does not store IPs except in rate-limit counters, which are deleted once their 10-minute window has ended; it does not log IPs or request bodies, and per-request invocation logs are disabled.
- **The operator controls the server code.** A modified server could log more metadata (IPs, exact times). It still cannot decrypt batches, because `K` never reaches the server.
- **The key must only be used in the app.** A web page served by the sync server (or from any origin the operator controls) must never ask for `K` or the recovery code, nor decrypt batches with it: the operator could change that page to exfiltrate the key, and the end-to-end encryption would be void.
- Admins can moderate shared check-ins (list and remove them), disable users and revoke devices. They cannot read encrypted data, and a modified or swapped blob fails to decrypt, because AES-GCM authenticates every blob and binds it to its slot. They can still delete data.

## Streams and records (inside the ciphertext)

- **Append-only streams.** The device tracks the highest uploaded SQLite rowid and uploads rows above it.
  - `fixes`: `{t, lat, lon, acc, alt, spd, brg, src}`
  - `motion`: `{s, e, std, mean, steps}`
  - `battery`: `{t, pct, chg, scr}`
- **Editable streams.** Uploaded as full row snapshots of rows changed since the last `updated_at` watermark. The latest snapshot per id wins; `deleted: true` marks a tombstone.
  - `places`: `{id, lat, lon, r, name, kind, ext, updatedAt}`
  - `checkins`: `{id, t, lat, lon, venue, cat, ext, place, comment, private, updatedAt}`
  - `overrides`: `{s, e, mode, updatedAt}`
- **Device status** (stream `status`, append-only): one record per successful small-stream sync run (hourly job and "sync now"), uploaded after the other small streams:
  `{t, battery:{pct, charging}, recording:{on, mode, since}, lastFix:{t, lat, lon, acc}|null, app:{versionName, versionCode}, device:{model}}`
  - `mode` is the logging mode key (`eco`, `balanced`, `precise`, `max`); `since` is when recording was switched on (0 if off or unknown).
  - `lat`/`lon` are rounded to 1e-6°, `acc` (metres, may be null) to 0.1 m. Readers ignore unknown fields and tolerate missing ones.
- **Raw GNSS logs** (stream `gnss`): `{file, part, parts, data}`, where `data` is base64 of a ≤ 4 MiB chunk of the GnssLogger text file.
- Ids are device-local. Consumers namespace them by device id.

## Other devices' status (download)

After each successful small-stream upload, a device downloads the latest status of the user's other devices. Failures here never fail or retry the upload.

- It refreshes `GET /v1/devices` (names, revocations).
- It walks `GET /v1/batches?after=<cursor>&limit=1000` from a cursor kept on the device, at most 20 pages per run, and keeps only the highest `seq` of `status` per other device that is newer than the one it has.
- It fetches just those blobs (`GET /v1/batches/:deviceId/status/:seq`) and opens them with the AAD of the source device, `"{deviceId}/status/{seq}"`, using the last record. Blobs of other streams are not downloaded.
- If a fetch fails on the network, the cursor is not saved, so the next run lists those batches again. A blob that fails to decrypt or parse is skipped.
- The receiving device keeps the latest status per device locally and may resolve an area name for the position locally. Nothing derived is uploaded.

## Network policy on the device

- **Small streams** (fixes, motion, battery, places, check-ins, overrides, status): WorkManager periodic job, every hour, `NetworkType.CONNECTED`.
  - With "モバイル回線でも同期" off, the job requires `NetworkType.UNMETERED` instead.
- **Raw GNSS logs**: a separate periodic job, every 6 h, requiring `NetworkType.UNMETERED`, and optionally charging.
- Both jobs use exponential backoff and resume from their cursors. Batches are at most 5 000 rows or 4 MiB of plaintext.

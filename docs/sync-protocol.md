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
  the web. Either an admin creates the user and hands them a pairing code, or,
  when the server allows it, the user's first device creates the user itself
  (see [Registration and limits](#registration-and-limits)).
- Data is isolated per user: a device only sees the devices and batches of its
  own user. Devices registered before users existed have no user; they only
  see each other, and cannot use shared check-ins or follows.
- Shared check-ins are the only data that crosses users: a user sees the
  check-ins shared by the users they follow (after those users accepted).
- Runtime: Cloudflare Workers. D1 stores users, devices, batch metadata,
  usage counters, follows and shared check-ins. R2 stores the encrypted batch
  blobs.
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
  - It is shown once as a **recovery code**: Crockford base32 of `K` plus a 2-byte checksum, in groups of 4. The checksum only catches typing errors.
  - A new device receives `K` by the user entering the recovery code. Transfer by a QR code shown on an existing device is not implemented yet.
  - On Android it is stored wrapped by a non-exportable Android Keystore AES key.
- **Batch key** = HKDF-SHA256(ikm=`K`, salt=empty, info=`"ashidori batch v1"`), 32 bytes.
- **Blob format**: `"ASH1"` (4 bytes) ‖ nonce (12 random bytes) ‖ AES-256-GCM(ciphertext ‖ 16-byte tag). A blob is therefore at least 32 bytes; the server refuses shorter ones.
  - The AAD is the UTF-8 string `"{deviceId}/{stream}/{seq}"`. A server that moves or replays a blob into another slot makes decryption fail.
  - The AAD does not stop a holder of `K` from sealing two different blobs for one slot. The server keeps exactly one per slot (see [Batches](#batches)), and clients compare a fetched blob with the listed SHA-256.
- **Plaintext**: gzip of UTF-8 JSON Lines, one record per line. The records per stream are listed below.
- Of a batch, the server sees only: device id, stream name, sequence number,
  blob size, blob SHA-256 and the upload time. D1 keeps the upload time
  rounded down to the hour; R2 keeps each object's exact upload time (see
  [What the server operator can see](#what-the-server-operator-can-see)).
  Time ranges of the data are inside the ciphertext.

## Authentication

- Each device has a **device token**: 32 random bytes, base64url without padding (43 characters, `^[A-Za-z0-9_-]{43}$`), sent as `Authorization: Bearer <token>`.
  - The device generates the token itself and stores it before it registers (see [Registration requests](#registration-requests)).
  - The server stores only `SHA-256(token)` (hex) and compares it in constant time.
- **Pairing**:
  - A pairing code is 8 characters of Crockford base32, single use, with a TTL of 10 minutes.
  - Every code is bound to a user, and the device registered with it belongs to that user.
  - Codes are created either by an existing device (`POST /v1/pairing`, for the device's own user) or by an admin for a given user (`POST /admin/users/:id/pairing`).
  - A code created by a device works only while that device is not revoked. Revoking a device also deletes the unused codes it created, in the same transaction. A code that an older server version stored without its issuing device is checked against the device named in its creator field; it is never taken for an admin's code.
  - A user's first device registers with a code from an admin, or, when registration is open, creates its user with `POST /v1/accounts`. Further devices always pair with a code.
- Tokens can be revoked per device. A revoked or unknown token gets `401`.
- An admin can disable a user. The user's device tokens then get `401` and the
  user's unused pairing codes are rejected, until the user is enabled again.
- **Rate limits**, in two layers:
  - A Workers Rate Limiting binding in front of D1, per Cloudflare location and 60-second window, eventually consistent. It only turns away floods cheaply, before any D1 access:
    - Registration and self-registration: per client IP, before the body is read.
    - Pairing and handle lookups: per client IP before the device token is checked (which reads D1), and once more per user after it.
    - A missing binding: self-registration with `REGISTRATION = open` is refused with `503 {"error": "server misconfigured"}` (fail closed; the admin page shows why). Elsewhere the check passes, and the server logs a warning once per isolate.
    - A binding that fails at runtime: the check passes, with a warning, and the D1 counters decide.
  - Precise fixed-window counters in D1, per client IP (IPv4 address, or the /64 prefix of an IPv6 address; IPv4-mapped IPv6 counts as IPv4): registration 10 per 10 minutes (requests with a well-formed body, wrong codes included), pairing 20 per 10 minutes, handle lookups 20 per 10 minutes, self-registration 5 per hour. A rejected request does not write to D1.
  - Over the limit: `429 {"error": "too many requests"}`.
  - Counters are keyed by IP and are deleted by a scheduled job within about 10 minutes after their window has ended.

### Registration requests

`POST /v1/devices/register` and `POST /v1/accounts` are idempotent per token, so
that a device whose response got lost can retry safely:

1. The device generates a token and stores it, together with the request, confirmed on disk, before sending. Registration is a single app-wide attempt; cancelling drops the answer of an attempt in flight (the server may still have created that device). The master key is confirmed on disk before sync is enabled.
2. The server stores the hash of that token for the new device and returns `201 {"deviceId": "..."}`. It never generates or returns a token.
3. A retry with the same token and the same request returns the same device: `200 {"deviceId": "..."}`.
   - Register: the same code, which that device consumed. The device must not be revoked.
   - Accounts: the token is that of the first device of a self-registered user.
4. This also holds for two identical requests in flight at once (a retry sent before the first response arrived): one creates the device (`201`), the other gets it (`200`), also when the first one took the code's last device slot or the server's last user slot (`MAX_USERS`). Before answering `401`, `403` or `409`, the server checks again whether the request's token now has a device that matches the request.
5. A used code with another token gives `401`, like an unknown code. A known token with another code also gives `401`. For `/v1/accounts`, a token that belongs to any other device gives `409 {"error": "token already in use"}`.

Consuming the code and creating the device happen in one D1 transaction: both or
neither. Used codes are kept for a day after they expire; a retry later than
that gets `401`. A self-registered account that stored nothing is deleted after
7 days (see below); a retry after that creates a new account.

## HTTP API

All bodies are JSON unless noted. Errors are `{"error": "..."}` with a suitable status.

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/admin/users` | admin | list users with usage (`storageBytes`, `batchCount`, `sharedCheckinCount`) |
| POST | `/admin/users` | admin | `{name, handle?, email?}` → the created user; `409` if the handle is taken; `503` while a limit variable is invalid |
| PATCH | `/admin/users/:id` | admin | `{name?, handle?}` (`handle: null` removes it) → the user |
| POST | `/admin/users/:id/pairing` | admin | → `{code, expiresAt}` for that user |
| POST | `/admin/users/:id/disable`, `/admin/users/:id/enable` | admin | disable / enable a user |
| GET | `/admin/devices` | admin | list all devices with their user |
| POST | `/admin/devices/:id/revoke` | admin | revoke any device (and delete its unused codes) |
| GET | `/admin/checkins` | admin | the 200 most recently changed shared check-ins (moderation) |
| POST | `/admin/checkins/:id/delete` | admin | remove a shared check-in for good (leaves a tombstone) |
| GET | `/v1/info` | – | `{registration: "admin" \| "open"}` |
| POST | `/v1/accounts` | – | `{token, deviceName?}` → `201 {deviceId}` (retry: `200`); creates a user with this device. `403 {"error": "registration closed"}` unless open; `403 {"error": "registration full"}` at `MAX_USERS`; `503` without the `RL_ACCOUNT` binding |
| POST | `/v1/devices/register` | – | `{code, name, token}` → `201 {deviceId}` (retry: `200`); the code is consumed. `401` for an invalid code; `409 {"error": "too many devices"}` at the user's device limit (the code is then not consumed) |
| POST | `/v1/pairing` | device | → `{code, expiresAt}` for the device's user |
| GET | `/v1/devices` | device | the user's devices: `[{id, name, createdAt, lastSeenAt, revoked}]` |
| POST | `/v1/devices/:id/revoke` | device | revoke one of the user's devices (including self) |
| PUT | `/v1/batches/:stream/:seq` | device | body: blob (`application/octet-stream`, 32 bytes to 8 MiB) |
| GET | `/v1/batches?after=<cursor>&limit=<n>` | device | metadata of the batches of all the user's devices, ordered by a global cursor |
| GET | `/v1/batches/:deviceId/:stream/:seq` | device | blob of one of the user's devices |
| PUT | `/v1/checkins/shared/:localId` | user device | share or update a check-in (see below) → `{id, cursor}` |
| DELETE | `/v1/checkins/shared/:localId?rev=<rev>` | user device | unshare this device's check-in (also one not shared yet) → `{deleted: true}`; `409` stale |
| DELETE | `/v1/checkins/shared/by-id/:id?rev=<rev>` | user device | unshare any of the user's check-ins by its server id → `{deleted: true}`; `409` stale |
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

- "user device" means a device that belongs to a user. Legacy devices without a user get `403 {"error": "no user"}`.
- Listings, fetches and revocations only cover the caller's user. A device of another user is reported like a missing one (`404`).
- While a limit variable of the server is invalid, requests that would create something (a user, also on the admin page, a device, a batch, a shared check-in or its tombstone) get `503 {"error": "server misconfigured"}`. Everything else keeps working, including unsharing an existing entry.

### Batches

- `stream` matches `^[a-z][a-z0-9_]{0,31}$`. `seq` is a non-negative integer (at most 12 digits) that the device assigns per stream, counting up from 0.
- `X-Content-SHA256` (hex, required) must match the body; else `400`. Bodies shorter than 32 bytes get `400 {"error": "blob too small"}`, longer than 8 MiB `413`.
- The device id comes from the token, never from the URL.
- Responses:
  - `201 {"status": "created", "cursor": n}`: stored.
  - `200 {"status": "exists"}`: the slot `(device, stream, seq)` already holds a blob with this SHA-256. Also when the user is at a limit.
  - `409`: the slot holds a different blob.
  - `507 {"error": "quota exceeded"}`: the user's byte quota or batch limit would be exceeded. `507 {"error": "server storage full"}`: the server-wide byte cap would be exceeded. Nothing is stored.
  - `503 {"error": "upload expired, retry"}` with `Retry-After`: the request took longer than the commit deadline (below). Nothing is stored; the client sends the same blob again.
- Storage is decided by one D1 statement: the slot is free, the user's counters leave room, and the server-wide counter leaves room. Concurrent uploads therefore never exceed a limit, and exactly one upload wins a slot.
  - The blob is first written to R2 under a key used by no other request, `b/{deviceId}/{stream}/{seq, 12 digits zero-padded}/{sha256}.{16 random hex digits}`, and then committed in D1 with that key. The slot's uniqueness decides the winner, and the winner's metadata references the winner's object. A request that does not commit deletes its object.
  - The cursor is assigned at the commit, so a listed batch always has a durable blob, and listings never skip a cursor that appears later.
  - Objects left behind by a request that stopped between the two steps are deleted by a scheduled sweep once they are older than the grace period `G` = 24 h (by the upload time R2 lists for the object) and still unreferenced.
  - **Commit deadline.** Before the R2 put, the request reads D1's clock (`start`). The commit statement also requires D1's clock, read inside that statement, to be at most `D` = 1 h after `start`. A request delayed longer, between the put and the commit or while the statement is in flight, is refused with `503`, and deletes its object. Without the deadline, a commit delayed beyond `G` could land after the sweep had judged its object unreferenced, leaving a listed batch whose blob is gone (found by formal/tla `BatchCommit_current`).
  - Why this is enough: R2's upload time of an object is after `start`, so when the sweep finds it older than `G` and unreferenced, D1's clock is past `start + G - 2·skew` (skew: the clock difference between D1, R2 and the Worker that runs the sweep), and a commit after `start + D` is refused. This holds while `D + 2·skew ≤ G`; with `D` = 1 h and `G` = 24 h, the clocks may differ by up to 11.5 h (formal/tla `BatchCommit_fixed` checks it with `D ≤ G` and one clock).
  - Batches stored before this scheme have no key in their metadata and stay at their old key, `b/{deviceId}/{stream}/{seq, 12 digits}`. They were not moved; `GET` falls back to that key for them.
- The list response is `{items: [{cursor, deviceId, stream, seq, size, sha256, createdAt}], next}`. `next` is null at the end.
  - The cursor is shared by all users, so a user's listing skips values. Clients only rely on it increasing.
- `GET` of a blob returns the committed object with `X-Content-SHA256` and `ETag` set to the listed hash.

## Registration and limits

The server variable `REGISTRATION` decides who creates users:

- `admin` (default): only admins, on the admin page. Any value other than exactly `open` (case and spaces matter), including an empty one, means `admin`.
- `open`: anyone may create a user from the app. The server is then only an end-to-end encrypted backup and sync target; no admin is involved.

`GET /v1/info` tells the app which mode the server is in, so that it can offer the right setup steps. A server without this endpoint is treated as `admin`.

`POST /v1/accounts {token, deviceName?}`:

- Creates a user and registers the calling device, authenticated by `token`, as its first device, in one transaction. See [Registration requests](#registration-requests) for retries.
- The user's name is the trimmed `deviceName` (1–64 characters, no control characters; else `400`). Without one, both names are `"device"`.
- The user has no handle (an admin may set one later) and is marked as self-registered (`createdBy: "self"` in the admin user list). Admins can disable such users like any other.
- Refused with `403 {"error": "registration full"}` once the server has `MAX_USERS` users (all users count; admins may still create users).
- A self-registered user that has stored nothing (no batch and no shared check-in) 7 days after its creation is deleted by a scheduled job, with its devices, pairing codes and follows. Users that stored anything are never deleted automatically.
- The app then creates `K` and shows the recovery code, as for any first device. Further devices pair from this device (`POST /v1/pairing`) and enter the recovery code.

### Limits

All limits are server variables. Empty means the default. Any other value must
be a positive integer; an invalid one (for example `0`, `-1` or `10MiB`) stops
the creation of users, devices, batches and shared check-ins (`503`) until it is
fixed, and the deploy workflow refuses to deploy it.

| Variable | Default | Limit |
|---|---|---|
| `USER_QUOTA_BYTES` | 2 GiB | blob bytes per user |
| `USER_MAX_BATCHES` | 200 000 | batches per user |
| `USER_MAX_DEVICES` | 50 | devices ever registered per user, revoked ones included |
| `USER_MAX_SHARED_CHECKINS` | 5 000 | shared check-ins per user, unshared ones (tombstones) included |
| `MAX_USERS` | 100 | users on the server, for self-registration |
| `MAX_TOTAL_BYTES` | 50 GiB | blob bytes on the server |

- Per-user limits count all the user's devices, revoked ones included (their data is kept). Legacy devices without a user share their data, so together they count as one user.
- Usage is kept in counters that change in the same statement as the data, so a check reads one row instead of summing the user's batches.
- The admin user list shows each user's usage; the admin page also shows the server totals.
- Clients treat `quota exceeded` and `server storage full` as persistent: they show it to the user and do not retry with backoff; the next scheduled run tries again (for example after the admin raised the limit).

## Shared check-ins and follows

A user can share selected check-ins with their followers. Shared check-ins are
**plaintext on the server**, by definition: the user chose to show them to
other people. Everything else stays end-to-end encrypted in batches.

### Follows

- Each user may have a **handle** (`^[a-z0-9_]{3,20}$`, unique, case-insensitive, stored lowercase), set by an admin. A user without a handle cannot be looked up.
- There is no user directory and no search. `GET /v1/users/lookup` matches the exact handle only and is rate limited, which limits bulk probing of handles. It does not prevent guessing: whoever tries a handle that exists learns that it exists and the user's name.
- `POST /v1/follows {userId}` sends a follow request (`pending`). It is idempotent and returns the current status. Following yourself is refused (`400`); an unknown or disabled user is `404`.
- The followee accepts (`accepted`) or declines the request. Declining deletes it, so the request can be sent again.
- Following is one-way. The follower can unfollow (or cancel a request); the followee can remove a follower.
- Disabled users cannot be looked up or followed, and do not appear in follow lists.
- `since` is when the follow was accepted (for requests: when it was sent), rounded down to the hour.

### Sharing

- `PUT /v1/checkins/shared/:localId` with `{rev, t, lat, lon, venue, category?, place?, comment?}`.
  - `localId` is the device-local check-in id and matches `^[A-Za-z0-9_-]{1,64}$`. It is scoped to the device: the same id from another device is another entry.
  - `rev`: the client's revision of the entry, a non-negative integer that increases with every change, for example the check-in's last change time in ms. Required.
  - `t`: check-in time, non-negative integer (ms). `lat` in [-90, 90], `lon` in [-180, 180].
  - `venue`: 1–200 characters after trimming. `category`, `place`: ≤ 200 characters. `comment`: ≤ 1000 characters; tabs and line breaks are allowed. Other control characters are rejected. Empty optional fields are stored as null.
  - The body is at most 4 KiB.
  - The server assigns the entry an `id`. Sharing the same `localId` again with a higher `rev` updates the entry (and reshares it if it was unshared). A higher `rev` with unchanged content only records the `rev`.
  - **Revisions**: each entry keeps the `rev` of the last PUT or DELETE applied to it, tombstones included. An operation applies only if its `rev` is above the stored one; the check and the change are one statement. Otherwise it changes nothing and gets `409 {"error": "stale revision"}`, so a delayed or replayed older PUT or DELETE cannot undo a later one. The exceptions are exact retries, which return `200`: a PUT with the same `rev` and content as the current state, and a DELETE with the same `rev` as the current tombstone. A client that gets `stale revision` treats its change as superseded.
  - A new entry beyond the user's `USER_MAX_SHARED_CHECKINS` gets `507 {"error": "too many shared check-ins"}`. Existing entries can still be changed.
- `DELETE /v1/checkins/shared/:localId?rev=<rev>` unshares this device's entry: it becomes a tombstone with `rev`, and the server deletes the content (time, position, venue, category, place, comment). Without `rev`, the server's current time in ms is used.
  - Unsharing a tombstone again with a higher `rev` only raises the `rev`; the cursor stays.
  - An unknown `localId` gets a tombstone with `rev` as well, so that a first PUT that arrives after the DELETE with a lower `rev` is refused as stale. It counts against `USER_MAX_SHARED_CHECKINS` (`507` at the limit). Followers see it as a deleted entry they never saw shared.
- `DELETE /v1/checkins/shared/by-id/:id?rev=<rev>` does the same by the server `id`, from any device of the entry's user, for example after the device that shared it was lost and revoked. An unknown id, or another user's entry, is `404`.
- **Removal by an admin is final.** The entry becomes a tombstone regardless of its `rev`. Its owner cannot share it again under the same `localId`, whatever `rev` it sends (`409 {"error": "removed by an admin"}`). An owner's DELETE of it returns `200` and changes nothing. The owner can still share the check-in anew under another local id. This applies to entries removed after this rule was introduced; earlier removals behave like an unshare.
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

- Every change to an entry moves it to a new, higher cursor: sharing, editing, unsharing, removal by an admin, and its owner being disabled or enabled. The server keeps only the latest state of each entry, so a client syncing with `after=<last cursor>` converges to the latest state of every entry. It does not see intermediate states of an entry that changed several times between two syncs.
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
  - users: name, handle, optional e-mail address, status, creation time, who created the user (an admin, or self-registration), and usage counters;
  - devices: id, name, registration time, last-seen time (rounded down to the hour);
  - batches in D1: device, stream name, sequence number, blob size, SHA-256, upload time (rounded down to the hour);
  - blobs in R2: the object key (device, stream, sequence number, SHA-256) and the **exact** upload time that R2 records for every object. The hour rounding applies to D1 only.
  - follows: who follows whom, with request and acceptance times (rounded down to the hour);
  - shared check-ins: **in plaintext**, as shared, with the hour of the last change and the client's `rev` (which may be the exact change time). Unshared entries keep only a tombstone and its `rev`.
- **Residual leaks**:
  - The number, size and upload times of batches per stream hint at activity, e.g. that the user was moving (more data), or that a device was offline.
  - Stream names and archive file names inside the ciphertext are hidden, but the stream name `archive` and the batch sizes tell roughly how much raw logging a user does.
  - The batch cursor is global. A user can see from the gaps between their own cursors roughly how many batches all other users uploaded meanwhile.
  - Cloudflare sees client IP addresses and request times at its edge. The Worker does not store IPs except in rate-limit counters, which a scheduled job deletes within about 10 minutes after their window has ended; it does not log IPs or request bodies, and per-request invocation logs are disabled.
- **The operator controls the server code.** A modified server could log more metadata (IPs, exact times). It still cannot decrypt batches, because `K` never reaches the server.
- **The key must only be used in the app.** A web page served by the sync server (or from any origin the operator controls) must never ask for `K` or the recovery code, nor decrypt batches with it: the operator could change that page to exfiltrate the key, and the end-to-end encryption would be void.
- Admins can moderate shared check-ins (list and remove them), disable users and revoke devices. They cannot read encrypted data, and a blob moved to another slot fails to decrypt, because AES-GCM authenticates every blob and binds it to its slot. They can still delete data, withhold it, or serve an older listing.
- **The device id in the AAD is assigned by the server.** A malicious server can give two of the user's devices the same id. Slot binding then only means that the blob was sealed for that slot by *some* device of the user that was given that id; a receiving device cannot tell those devices apart. Confidentiality is not affected (formal/crypto, `same_device_id_for_two_devices`).
- **No forward secrecy.** Every batch of every device is sealed under keys derived from the one `K`. Whoever learns `K` later can decrypt all past ciphertext the server kept, as well as future batches. Revoking a device revokes its token, not `K`: a revoked device, or a leaked recovery code, still opens everything (formal/crypto, `no_forward_secrecy`). Replacing `K` would need a new account key and re-uploading the history; this is not implemented.
- The listing is not trusted for liveness either: a server can list rows that do not exist or jump the cursor. The status download keeps several candidates per device and rescans the listing (see [Other devices' status](#other-devices-status-download)), so such rows delay but do not stop it once the server stops making them up. Against a server that withholds data for good, nothing helps.

## Streams and records (inside the ciphertext)

The records below describe what the current app uploads. Readers ignore unknown
fields and tolerate missing ones. Ids are device-local; consumers namespace them
by device id.

- **Editable streams** (`places`, `checkins`, `overrides`): rows changed since the last uploaded change, in the order of a device-local change counter, as full row snapshots. A batch holds at most 5 000 rows or 4 MiB of plaintext. The latest snapshot per id wins. Deleted rows are not uploaded.
  - `places`: `{id, lat, lon, r, name, kind, ext, updatedAt}`
  - `checkins`: `{id, t, lat, lon, venue, cat, ext, place, comment, private, updatedAt}`
  - `overrides`: `{s, e, mode, updatedAt}`
- **Device status** (stream `status`): one record per successful small-stream sync run (hourly job and "sync now"), uploaded after the other small streams:
  `{t, battery:{pct, charging}, recording:{on, mode, since}, lastFix:{t, lat, lon, acc}|null, app:{versionName, versionCode}, device:{model}}`
  - `mode` is the logging mode key (`eco`, `balanced`, `precise`, `max`); `since` is when recording was switched on (0 if off or unknown).
  - `lat`/`lon` are rounded to 1e-6°, `acc` (metres, may be null) to 0.1 m.
- **Archive** (stream `archive`): the logged signals are not uploaded row by row. The device keeps the current day in SQLite; once a day is over, it writes each table of that day to a Parquet file `<table>/YYYY-MM-DD.parquet`, and once a month is over, it merges the month's day files into `<table>/YYYY-MM.parquet`. Each such file is uploaded once, split into chunks of at most 3 MiB, one batch per chunk:
  `{file: "<table>/<YYYY-MM-DD or YYYY-MM>.parquet", gen, part, parts, data}`
  - `data` is base64 of the chunk; concatenating `part` 0 to `parts - 1` of one (`file`, `gen`) gives the Parquet file.
  - `gen` identifies the file version (hex, derived from size and modification time). If a file changes while it is being uploaded, it is sent again from part 0 under a new `gen` and new seqs. A reader assembles parts per (`file`, `gen`) and uses a complete generation.
  - Each part is a separate batch, committed to the outbox and sent on its own.
  - A month file is not uploaded if all its days were uploaded as day files. A restorer combines day and month files of a table and drops exact duplicate rows.
  - Tables and columns (the first column is the time in ms, except where noted):
    - `fixes`: `time, lat, lon, acc, alt, speed, bearing, provider`
    - `motion`: `start, end, std, mean, steps`
    - `battery`: `time, pct, charging, screen_on, mode`
    - `gnss_status`: `time, visible, used, top4, l5, env, acc`
    - `gnss_raw`: the columns of a GnssLogger `Raw` line
    - `gnss_sat`: `UnixTimeMillis, SignalCount, SignalIndex, ConstellationType, Svid, CarrierFrequencyHz, Cn0DbHz, AzimuthDegrees, ElevationDegrees, UsedInFix, HasAlmanacData, HasEphemerisData, BasebandCn0DbHz`
    - `gnss_nav`: `time, Svid, Type, Status, MessageId, SubmessageId, Data` (`time` is the preceding record's time)
    - `nmea`: `time, message`
    - `imu`: `time, elapsedRealtimeNanos, sensor (Accel, Gyro or Mag), x, y, z`
  - The small-stream job uploads `fixes`, `motion`, `battery` and `gnss_status`; the raw-log job uploads `gnss_raw`, `gnss_sat`, `gnss_nav`, `nmea` and `imu`.
- Seqs of a stream can have gaps (see the upgrade rule under "Upload outbox on the device").
- Earlier app versions uploaded raw GNSS logs as stream `gnss` (`{file, part, parts, data}` with chunks of the GnssLogger text file) and the signal tables as JSON Lines streams `fixes`, `motion` and `battery`. Servers may still hold such batches.

### What a backup can restore

- Editable data (places, check-ins, overrides) up to the last successful sync, except deletions.
- Logged signals of the days that were archived and uploaded. The current day is not uploaded until it is archived after midnight, so a device lost during the day loses that day's positions, motion and battery records, even right after a successful sync.
- Rows that arrive for a day after its file was uploaded are merged into the device's file but not uploaded again.
- The app does not restore from the server yet. It only downloads the latest `status` of the user's other devices (below). The formats above are what a restorer will read.

## Other devices' status (download)

After each successful small-stream upload, a device downloads the latest status of the user's other devices. Failures here never fail or retry the upload.

- It refreshes `GET /v1/devices` (names, revocations).
- It walks `GET /v1/batches?after=<cursor>&limit=1000` from a cursor kept on the device, at most 20 pages per run. The cursor is only a fetch position and only moves to cursors of rows actually listed.
- The cursor does not move past a row whose cursor is not above the previous one (the page's first row: above `after`), nor, on a page with fewer than `limit` rows (the end of the listing), past a row whose cursor is more than 10,000,000 above the previous one. The rows of such a page are still taken as candidates, the page is re-listed on the next run, and a rescan is wanted. A full page is always followed, since an honest gap at its start would otherwise stop the walk for good. (The cursor is shared by all users, so honest gaps exist, but they stay far below that bound.)
- Per other device it keeps candidates: listed `status` rows above the highest seq that decrypted and parsed, each with its seq, size, SHA-256 and failure count. Two rows with the same seq but another size or SHA-256 are two candidates. It keeps the newest 4 still to try and, separately, the newest 4 exhausted ones (below), so exhausted rows neither crowd out the others nor come back as new when listed again.
- It fetches one blob per device and run, that of the newest candidate that is not exhausted (`GET /v1/batches/:deviceId/status/:seq`), checks that its size and SHA-256 match the listing (rows without `size` and `sha256` are malformed and fail the page), and opens it with the AAD of the source device, `"{deviceId}/status/{seq}"`, using the last record. Blobs of other streams are not downloaded.
- A network error changes nothing, so the next run tries again. A status that cannot be read (size or hash mismatch, decrypt or parse failure, `404`, over-size) is *unresolved*: its candidate is retried up to 3 times and is then *exhausted*, and the next newest candidate is tried. The newest unresolved status is shown on the device's row until a status of that device at least as new is read. It is never skipped silently, and the read seq only increases on success.
- **Rescan.** Now and then the device walks the listing again from `after=0`, with the same page budget per run (shared with the normal walk), continuing on later runs. A rescan starts 7 days after the previous one, or 1 day after it when one is wanted (a page broke the cursor rule above, or a device has no candidate left to try). When it reaches the end of the listing, or a page does not move it on, the cursor becomes where it ended. A row the server made up (a huge seq or a huge cursor) therefore delays, but does not stop, the download of later statuses once the server stops listing it, and a made-up seq is passed over by the older candidates even while it is listed (formal/tla `StatusDownload_fixed`; review #7).
- Size caps, enforced while reading: a listing page ≤ 1 MiB and ≤ `limit` rows; a status blob ≤ 64 KiB; decompressed status ≤ 64 KiB and ≤ 64 JSON lines; other JSON responses ≤ 1 MiB.
- The receiving device keeps the latest status per device locally. It may resolve an area name for the position with the platform `Geocoder`, which can contact a network geocoding provider. Nothing derived is uploaded to the sync server.

## Upload outbox on the device

- Building a batch atomically claims the next seq, stores the ciphertext in a local outbox table and records the source progress (the change counter for editable streams, the uploaded mark for archive files), in one SQLite transaction. All chunks of one archive file are built in one transaction.
- An outbox row is deleted only after `200` or `201`. A retry sends the identical bytes, so a seq is never reused for different ciphertext.
- Rows carry a class, `small` or `raw`; `raw` rows are only sent by the raw-log job, under its network conditions.
- All sync runs (periodic, "sync now", raw logs, the status download and unpairing) are serialized on the device.
- Editable streams are tracked by a device-local change counter, not `updatedAt` (equal timestamps or a clock moved back would skip rows). Records still carry `updatedAt`.
- Encryption happens outside the database transaction; the transaction then commits the outbox row, the seq increment and the progress mark, and refuses if the seq moved meanwhile.
- Archive enqueueing pauses while a class's unsent outbox exceeds 64 MiB.
- Upgrade from a version without the outbox: legacy pending blobs are discarded unsent and legacy progress marks are dropped, so all archive files and editable tables are uploaded again under new seqs above anything the old version used. Readers must tolerate duplicate files and rows. No ciphertext sealed by an older version is ever sent.
- Sync progress is kept per registration (server URL and device id). Unpairing cancels and waits for running jobs, then clears all progress and unsent batches, so a new registration uploads everything again and never sends ciphertext bound to the old device id.

## Network policy on the device

- **Small streams** (places, check-ins, overrides, the archives of fixes, motion, battery and GNSS status, and status): WorkManager periodic job, every hour, `NetworkType.CONNECTED`.
  - With "モバイル回線でも同期" off, the job requires `NetworkType.UNMETERED` instead.
- **Raw logs** (the archives of raw GNSS, satellite status, navigation messages, NMEA and IMU): a separate periodic job, every 6 h, requiring `NetworkType.UNMETERED`, and optionally charging.
- Both jobs use exponential backoff and resume where they stopped.
- `quota exceeded` and `server storage full` are not retried with backoff: the job ends, the error is shown in the sync settings, and the next periodic run tries again.

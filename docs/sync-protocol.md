# ashidori sync protocol (v1)

ashidori works fully offline. Sync is optional: when a server is configured, each
device uploads its history so that it is backed up and visible from other
devices and the web viewer.

Location history is sensitive, so the server only ever stores ciphertext.
Cloudflare (or anyone with access to the account) cannot read positions,
check-ins or place names.

## Deployment model

- One deployment serves one person (a personal server). Several devices belong
  to that person; there is no multi-user tenancy.
- Runtime: Cloudflare Workers. D1 stores batch metadata and devices. R2 stores
  the encrypted batch blobs.
- Admin routes (`/admin/*`) are protected by Cloudflare Access. The Worker also
  verifies the `Cf-Access-Jwt-Assertion` JWT (audience `CF_ACCESS_AUD`, issuer
  `https://<CF_ACCESS_TEAM_DOMAIN>`), so a misconfigured route does not expose them.

## Keys and encryption

- **Account master key `K`**: 32 random bytes, generated on the first device.
  - It never leaves the user's devices and browser in plaintext.
  - It is shown once as a **recovery code**: Crockford base32 of `K` plus a 2-byte checksum, in groups of 4.
  - New devices receive it through a QR code shown on an existing device.
  - On Android it is stored wrapped by a non-exportable Android Keystore AES key.
- **Batch key** = HKDF-SHA256(ikm=`K`, salt=empty, info=`"ashidori batch v1"`), 32 bytes.
- **Blob format**: `"ASH1"` (4 bytes) ‖ nonce (12 random bytes) ‖ AES-256-GCM(ciphertext ‖ 16-byte tag).
  - The AAD is the UTF-8 string `"{deviceId}/{stream}/{seq}"`. A server that swaps or replays blobs between slots makes decryption fail.
- **Plaintext**: gzip of UTF-8 JSON Lines, one record per line. The record schema per stream is listed below.
- The server sees only: device id, stream name, sequence number, blob size,
  blob SHA-256 and upload time. Time ranges of the data are inside the
  ciphertext.

## Authentication

- Each device has a **device token**: 32 random bytes, base64url, sent as
  `Authorization: Bearer <token>`.
  - The server stores only `SHA-256(token)` (hex).
  - The token is compared in constant time against the stored hash.
- **Pairing**:
  - A pairing code is 8 characters of Crockford base32, single use, with a TTL of 10 minutes.
  - Codes are created either by an existing device (`POST /v1/pairing`) or from the admin page (`POST /admin/pairing`, behind Access).
  - The first device is always paired through the admin page.
- Tokens can be revoked per device. A revoked or unknown token gets `401`.
- Pairing and registration are rate limited per IP.

## HTTP API

All bodies are JSON unless noted. Errors are `{"error": "..."}` with a suitable status.

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/admin/pairing` | Access | → `{code, expiresAt}` |
| GET | `/admin/devices` | Access | list devices |
| POST | `/admin/devices/:id/revoke` | Access | revoke a device |
| POST | `/v1/devices/register` | – | `{code, name}` → `{deviceId, token}`; the code is consumed |
| POST | `/v1/pairing` | device | → `{code, expiresAt}` |
| GET | `/v1/devices` | device | `[{id, name, createdAt, lastSeenAt, revoked}]` |
| POST | `/v1/devices/:id/revoke` | device | revoke (including self) |
| PUT | `/v1/batches/:stream/:seq` | device | body: blob (`application/octet-stream`, ≤ 8 MiB) |
| GET | `/v1/batches?after=<cursor>&limit=<n>` | device | metadata of all devices' batches, ordered by a global cursor |
| GET | `/v1/batches/:deviceId/:stream/:seq` | device | blob |
| GET | `/v1/health` | – | `{ok: true}` |

- `stream` matches `^[a-z][a-z0-9_]{0,31}$`. `seq` is a non-negative integer that the device assigns per stream, counting up from 0.
- `PUT` is idempotent. If the same `(device, stream, seq)` already exists with the same SHA-256, the server returns `200 {"status":"exists"}`. If it exists with a different hash, the server returns `409`. A new upload returns `201`.
  - The device id comes from the token, never from the URL.
  - The server records `X-Content-SHA256` (hex, required) and verifies it against the body.
- The list response is `{items: [{cursor, deviceId, stream, seq, size, sha256, createdAt}], next}`. `next` is null at the end.
- R2 key: `b/{deviceId}/{stream}/{seq as 12-digit zero padded}`.

## Streams and records (inside the ciphertext)

- **Append-only streams.** The device tracks the highest uploaded SQLite rowid and uploads rows above it.
  - `fixes`: `{t, lat, lon, acc, alt, spd, brg, src}`
  - `motion`: `{s, e, std, mean, steps}`
  - `battery`: `{t, pct, chg, scr}`
- **Editable streams.** Uploaded as full row snapshots of rows changed since the last `updated_at` watermark. The latest snapshot per id wins; `deleted: true` marks a tombstone.
  - `places`: `{id, lat, lon, r, name, kind, ext, updatedAt}`
  - `checkins`: `{id, t, lat, lon, venue, cat, ext, place, comment, private, updatedAt}`
  - `overrides`: `{s, e, mode, updatedAt}`
- **Raw GNSS logs** (stream `gnss`): `{file, part, parts, data}`, where `data` is base64 of a ≤ 4 MiB chunk of the GnssLogger text file.
- Ids are device-local. Consumers namespace them by device id.

## Network policy on the device

- **Small streams** (fixes, motion, battery, places, check-ins, overrides): WorkManager periodic job, every hour, `NetworkType.CONNECTED`.
  - With "モバイル回線でも同期" off, the job requires `NetworkType.UNMETERED` instead.
- **Raw GNSS logs**: a separate periodic job, every 6 h, requiring `NetworkType.UNMETERED`, and optionally charging.
- Both jobs use exponential backoff and resume from their cursors. Batches are at most 5 000 rows or 4 MiB of plaintext.

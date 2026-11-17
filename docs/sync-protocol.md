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
  the web. Every user is created by their first app device, under a username
  chosen there: with an invite from an admin, or without one when the server
  allows it (see [Registration and limits](#registration-and-limits)). There
  is no browser-first signup, and admins do not create users.
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
  - **The web never receives `K`, the recovery code, or the blob authentication key.** The one exception to "only the app" is the **batch key**, and only after the user explicitly opted in with 「ブラウザでも見る」 and approved one browser: the app then seals the batch key (not `K`) to that browser (see [Browser viewers](#browser-viewers)).
  - It is shown once as a **recovery code**: Crockford base32 of `K` plus a 2-byte checksum, in groups of 4. The checksum only catches typing errors.
  - A new device receives `K` by the user entering the recovery code. Transfer by a QR code shown on an existing device is not implemented yet.
  - On Android it is stored wrapped by a non-exportable Android Keystore AES key.
- **Batch key** = HKDF-SHA256(ikm=`K`, salt=empty, info=`"ashidori batch v1"`), 32 bytes.
- **Blob authentication key `Ka`** = HKDF-SHA256(ikm=`K`, salt=empty, info=`"ashidori batch auth v1"`), 32 bytes. Only apps hold it; it is never given to a browser (see [Blob authentication](#blob-authentication-v11)).
- **Blob format**: apps write **ASHM**: `"ASHM"` (4 bytes) ‖ nonce (12 random bytes) ‖ AES-256-GCM(ciphertext ‖ 16-byte tag) ‖ mac (32 bytes), with the mac defined in [Blob authentication](#blob-authentication-v11). Apps before v1.1 wrote **ASH1**: `"ASH1"` (4 bytes) ‖ nonce ‖ AES-256-GCM(ciphertext ‖ tag), the same without the mac. Both formats use the batch key and the AAD below for GCM. A blob is therefore at least 32 bytes (ASH1) or 64 bytes (ASHM); the server refuses blobs shorter than 32 bytes and does not look at the format.
  - The AAD is the UTF-8 string `"{deviceId}/{stream}/{seq}"`. A server that moves or replays a blob into another slot makes decryption fail.
  - The AAD does not stop a holder of `K` from sealing two different blobs for one slot. The server keeps exactly one per slot (see [Batches](#batches)), and clients compare a fetched blob with the listed SHA-256.
- **Plaintext**: gzip of UTF-8 JSON Lines, one record per line. The records per stream are listed below.
- Of a batch, the server sees only: device id, stream name, sequence number,
  blob size, blob SHA-256 and the upload time. D1 keeps the upload time
  rounded down to the hour; R2 keeps each object's exact upload time (see
  [What the server operator can see](#what-the-server-operator-can-see)).
  Time ranges of the data are inside the ciphertext.

### Blob authentication (v1.1)

GCM only shows that a blob was made by a holder of the batch key. Once a browser holds the batch key ([Browser viewers](#browser-viewers)), that is no longer only the user's apps: a leaked batch key, together with the server, could make ASH1 blobs for any slot. ASHM adds a MAC under `Ka`, which only apps hold (design: [`design/browser-pairing-v1.md`](design/browser-pairing-v1.md#blob-の認証v11); formal/crypto `batch.spthy`, `-D=ASH1`).

- **mac** = HMAC-SHA256(`Ka`, `"ashidori blob auth v1"` ‖ len(AAD) (2 bytes, big-endian) ‖ AAD ‖ every byte of the blob before the mac), where AAD is the slot's `"{deviceId}/{stream}/{seq}"` and the label is ASCII without a terminator.
- **Writers**: every app since v1.1 seals ASHM. Batches an older version had already sealed into the outbox are sent unchanged (as ASH1); they keep their seqs, which are below those of the stream's ASHM batches.
- **Apps reading**: an ASHM blob is accepted only if its mac matches (compared in constant time), then decrypted. An ASH1 blob is accepted only under the cut-over rules below.
- **Viewers reading**: they strip the last 32 bytes unchecked and decrypt the GCM part. They cannot check the mac and so cannot authenticate the writer until v2 (writer signatures).
- **Cut-over record `C`** (stream `authcut`, an ASHM batch of the app that made it): gzip of JSON Lines `{deviceId, stream, below, digest}`, one line per (device, stream) pair listed when it was made, except `authcut`. `below` is the largest seq listed for the pair + 1 (at most 1 000 000), and `digest` = SHA-256 of the listed SHA-256 (32 raw bytes each) of seq 0 to `below - 1` in seq order, 32 zero bytes for a seq not listed.
  - The app that approves the first browser makes `C` before it approves: it sends its own outbox, reads the whole listing, and uses a `C` that already exists (named in a status or listed, and verifying as ASHM). It makes a new `C` only in the first-time state: the state was not lost, and no `C` was ever named, listed (verified or not), or listed beyond the 8 candidates kept. Otherwise it only fetches and verifies an existing `C`, and if none verifies it refuses with 「切り替えの記録を確認できません。ほかの端末で同期してから再試行してください」 (a server hiding the real `C` could otherwise get a new one made over ASH1 it forged). In the first-time state, every other device that is neither revoked nor a viewer must have had an ASHM blob accepted by this app (its status, downloaded first); if not, it refuses with 「すべての端末のアプリを更新してください」. It then stores `C` for upload, records it as its own (required state, on disk), and returns only once `C` is on the server.
  - **Status** records carry `authcut: {deviceId, seq, sha256}`, the `C` the device knows, so the other apps learn of it.
- **Cut-over state** of an app, per registration and in its own file (it survives restarts): the required flag, `C` and its verified content, per pair the smallest seq at which ASHM was accepted, and per pair the ASH1 blobs accepted before the required state (seq and SHA-256, the newest 16).
  - **Persistence**: a state whose write failed (outcome unknown or not) is read as the stricter, new state but is not persisted: the next update writes it even if it changes nothing, and making or reusing `C` before an approval returns only once the required state is on disk. A file that cannot be read or does not parse at start, or a stored state that does not parse, is not a first run: the state is required with nothing known, so no ASH1 is accepted until an existing `C` is fetched, verified and adopted again; the cut-over does not make a new one in this state.
  - **Required state**: entered when the app made `C`, fetched and verified `C`, or accepted an ASHM status that names `C`, and stored before that status counts as read. It is never left (until the device unpairs). Entering it drops the ASH1 blobs accepted before: from then on every ASH1 blob is judged against `C`, and statuses accepted as ASH1 before are no longer shown.
  - **Order within a run**: the app fetches the statuses first, then opens every ASHM one and stores what it names, then fetches the `C` still to fetch (named or listed) and makes sure the state is on disk; only then it judges the ASH1 ones. If a store or the fetch of `C` failed, ASH1 waits for the next run. So it does while no `C` is verified and a candidate is left untried (the limit of 4 tries per run), or more candidates were listed than the 8 kept.
  - **ASH1**, checked in this order: not required and accepted before as the same blob: accepted. Above the smallest seq at which ASHM of the pair was accepted: refused, also before the required state. Not required: accepted. Required and `C` not fetched yet, the pair not in `C`, or seq ≥ `below`: refused. Otherwise the app reads the listing rows of the pair (at most 200 pages per run; a longer listing refuses) and accepts only if their digest equals `C`'s and the blob is the one listed at that seq.
  - A refused ASH1 status is unreadable like any other (see [Other devices' status](#other-devices-status-download)).
- **Residual risks**:
  - An app that never receives `C` nor a status naming it (the server hides both) never enters the required state, and accepts forged ASH1 of a pair from which it never accepted ASHM (e.g. a device the server made up).
  - A device registered before `C` has no history to compare, with the same limit.
  - An ASHM blob with its mac stripped and its magic set back to ASH1 can be accepted before the required state; its content is genuine, so this is no forgery.
  - An ASH1 batch that was still in a device's outbox when `C` was made (e.g. raw logs waiting for an unmetered network) is listed later in a seq below `below`; the pair's digest then no longer matches, and its ASH1 batches not accepted before are refused. The app downloads only `status` (sent last in each run, after the rest of the small outbox), so this does not affect it; a future restorer must expect it.

## Authentication

- Each device has a **device token**: 32 random bytes, base64url without padding (43 characters, `^[A-Za-z0-9_-]{43}$`), sent as `Authorization: Bearer <token>`.
  - The device generates the token itself and stores it before it registers (see [Registration requests](#registration-requests)).
  - The server stores only `SHA-256(token)` (hex) and compares it in constant time.
- **Pairing**:
  - A pairing code is 8 characters of Crockford base32, single use, with a TTL of 10 minutes. The code's creation, its expiry and the check whether it has expired all use D1's clock, and the check runs inside the statement that consumes the code: a request delayed between the Worker and D1 cannot consume a code that expired meanwhile.
  - Every code is bound to a user, and the device registered with it belongs to that user.
  - Codes are created either by an existing device (`POST /v1/pairing`, for the device's own user) or by an admin for a given user (`POST /admin/users/:id/pairing`, for example a legacy user without a device).
  - A code created by a device works only while that device is not revoked. Revoking a device also deletes the unused codes it created, in the same transaction. A code that an older server version stored without its issuing device is checked against the device named in its creator field; it is never taken for an admin's code.
  - A user's first device creates its user with `POST /v1/accounts`: with an invite (below) on any server, without one only when registration is open. Further devices always pair with a code.
- **Invites**: an admin's single-use permission to create one user.
  - An invite code is 16 characters of Crockford base32 (80 random bits), created by `POST /admin/invites` with an expiry of 1 to 30 days (default 7). The server stores only its SHA-256. Like a pairing code, its creation, its expiry and the expiry check use D1's clock, and the check runs inside the statement that consumes it.
  - It is consumed by the `POST /v1/accounts` that creates its user, in the same transaction that creates the user and the first device: all three or none. It is bound to no user before that, and to that user after it.
  - An admin can revoke an unused invite. A revoked, expired, used or unknown invite gets `401 {"error": "invalid or expired invite"}`, all alike.
- **Usernames**: every user has one username, chosen on the first device: `^[a-z0-9_]{3,20}$`, unique, case-insensitive (input is trimmed and lowercased; stored lowercase). It is what the app and the admin page show, and what other users look a user up by; there is no separate display name. Users created before usernames may have none (legacy) until an admin sets one. The API calls it `username`; the D1 column keeps its earlier name `users.handle`.
- Tokens can be revoked per device. A revoked or unknown token gets `401`.
- Devices have a **kind**: `device` (an app) or `viewer` (a browser that received the batch key; read-only, with an expiry). See [Browser viewers](#browser-viewers).
- An admin can disable a user. The user's device tokens then get `401` and the
  user's unused pairing codes are rejected, until the user is enabled again.
- **Rate limits**, in two layers:
  - A Workers Rate Limiting binding in front of D1, per Cloudflare location and 60-second window, eventually consistent. It only turns away floods cheaply, before any D1 access:
    - Registration, registration with an invite, and self-registration: per client IP, after the body is validated and after the result recovery (see [Registration requests](#registration-requests)), before anything is written to D1. The result recovery reads D1 once per well-formed request, as the device authentication of every authenticated route does.
    - Pairing and username lookups: per client IP before the device token is checked (which reads D1), and once more per user after it.
    - A missing binding: self-registration with `REGISTRATION = open` is refused with `503 {"error": "server misconfigured"}` (fail closed; the admin page shows why). Elsewhere the check passes, and the server logs a warning once per isolate.
    - A binding that fails at runtime: the check passes, with a warning, and the D1 counters decide.
  - Precise fixed-window counters in D1, per client IP (IPv4 address, or the /64 prefix of an IPv6 address; IPv4-mapped IPv6 counts as IPv4): registration 10 per 10 minutes (requests with a well-formed body, wrong codes included), registration with an invite 10 per 10 minutes (likewise), pairing 20 per 10 minutes, username lookups 20 per 10 minutes, self-registration 5 per hour. A rejected request does not write to D1.
    - The window is taken from D1's clock in the statement that counts the request. A counter's window only moves forward: a request whose window is older than the stored one (from a server version that still uses its own clock, or after D1's clock stepped back) is counted in the stored window and never resets the counter.
  - Over the limit: `429 {"error": "too many requests"}`.
  - Counters are keyed by IP and are deleted by a scheduled job within about 10 minutes after their window has ended.

### Registration requests

`POST /v1/devices/register` and `POST /v1/accounts` are idempotent per token, so
that a device whose response got lost can retry safely:

1. The device generates a token and stores it, together with the request, confirmed on disk, before sending. Registration is a single app-wide attempt; cancelling drops the answer of an attempt in flight (the server may still have created that device). The master key is confirmed on disk before sync is enabled.
   - Taking in an answer and cancelling are one compare-and-set each on the attempt's state: the answer moves it from in flight to saving, the cancel from in flight to none. Exactly one of them wins. Once a cancel returned as successful, nothing of that attempt is saved, and only then is the stored request removed. A cancel that finds the answer being saved reports that it came too late and changes nothing: the request stays, and if saving the answer fails, that failure is reported as the attempt's own and the request can be sent again with the same token. Unpairing invalidates the attempt first, on the same executor that saves answers, then clears the registration. Results carry their attempt id, and the screen ignores results of an attempt that is no longer the latest.
   - `SharedPreferences.commit()` updates the in-memory copy before it writes the disk and keeps that update when the write fails, and any later write to the same file persists it. The registration (URL, device id, self-registration flag, unpairing mark) and the secrets (token, master key, pending request) are therefore not kept in SharedPreferences. Each group is one file owned by one store, which replaces it as a whole (temporary file, `fsync`, rename) and publishes an immutable snapshot of its records only after the file was written. A reader sees the records of the last write that reached the disk, never a value being written or whose write failed, and never waits for the disk. After a failure or a crash the file holds the old or the new records, so nothing has to be undone and a restart reads only values that were on disk. If a failed write cannot be read back to tell which, the keys it changed are unknown: they read as absent, except the unpairing mark, which reads as set (the file may still hold it, and the next start would then finish the unpairing over whatever was registered meanwhile). The next write reads the file again and starts from the records it holds, so it never writes back a value that the failed write may have replaced; if the file still cannot be read, a write that would have to guess an unknown key is refused. The device counts as paired or ready (for the settings screen and for the sync jobs) only on values that are on disk.
   - The master key is stored with the registration it was set for (a hash of the server URL and device id) and counts only for that registration: the device is ready only when the stored key's registration is the current one. A key left over from an earlier registration is never used for a later one, and saving a new registration never adopts it. A key stored by an earlier version without its registration is bound to the current registration once, at start.
2. The server stores the hash of that token for the new device and returns `201 {"deviceId": "..."}` (`/v1/accounts`: `{"deviceId", "username"}`). It never generates or returns a token.
3. A retry with the same token and the same request returns the same device: `200` with the same body.
   - Register: the same code, which that device consumed. The device must not be revoked.
   - Accounts: the token is that of the first device (not revoked) of a user created by the same kind of request: with the same invite, or self-registered without one. The username is not compared, and is not even required for the recovery (a request stored by an older app has none); the answer carries the user's username as it is now.
   - **Result recovery is separate from the permission to create.** The server answers a retry from what is stored, before any gate that decides whether something new may be created: `REGISTRATION`, the edge rate limiter (and whether its binding exists), the D1 rate-limit counters, `MAX_USERS`, the user's device limit and invalid limit variables. A device whose response got lost therefore gets its id back even after the operator closed registration, while the client is rate limited, or once the server is full. Only a request that would create a user or a device is gated. A request with an unknown token, or with a known token that does not match the request, goes through the gates like any other and is counted by the rate limits.
4. This also holds for two identical requests in flight at once (a retry sent before the first response arrived): one creates the device (`201`), the other gets it (`200`), also when the first one took the code's last device slot, the invite, the username or the server's last user slot (`MAX_USERS`), or when the operator closed registration after the first one created the account. Before answering `401`, `403` or `409`, the server checks again whether the request's token now has a device that matches the request (formal/tla `Accounts_closedrace`, `Invites`).
   - Not covered: a copy refused before the other copy committed. For `/v1/accounts` without an invite, `REGISTRATION` is read by the Worker, not inside the transaction, so a copy that reaches it before the other copy created the account gets `403 registration closed` (or `registration full`), and the account is created afterwards. The client therefore takes a `403` from `/v1/accounts` as final only for the first request with a token it made in this process and never sent before. A token that may have been sent before (an earlier attempt whose answer was lost, a retry, a request stored before a crash) is sent again with growing intervals (2 s, 4 s, … up to 30 s) for 2 minutes after a `403`; if the other copy created the account meanwhile, recovery answers `200`. Only then is the request dropped as refused. With an invite, `REGISTRATION` is not read, and every refusal (`401`, `409`) is decided inside or after the transaction.
5. A used code with another token gives `401`, like an unknown code. A known token with another code also gives `401`. An invite used by another token gives `401`, like an unknown invite. For `/v1/accounts`, a token that belongs to any other device (including the first device of a user created with another invite) gives `409 {"error": "token already in use"}`, and a username that another user has gives `409 {"error": "username taken"}`.

Consuming the code and creating the device happen in one D1 transaction: both or
neither. Likewise consuming the invite, creating the user and creating its first
device; a username conflict rolls all of it back, so the invite stays unused.
Used codes are kept for a day after they expire; a retry later than that gets
`401`. Used invites are kept. A self-registered account (without an invite) that
stored nothing is deleted after 7 days (see below); a retry after that creates a
new account.

### Registration links

A pairing code or an invite can be handed over as a link that also names the server:

- `https://<server>/pair#c=<CODE>` (pairing code: add a device to a user) or `https://<server>/pair#i=<INVITE>` (invite: register a new user): shown with every new code, on the admin page and in the app, as text and as a QR code.
  - The code is in the **fragment**. Browsers never send the fragment, so opening the link does not put the code in the request line, in a `Referer` or in the server's logs. The server never puts the code in a path or query.
  - The fragment is exactly `c=<CODE>` or exactly `i=<INVITE>`, the value being exactly what the server issued, in uppercase Crockford base32 without hyphens: `<CODE>` 8 characters (`^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{8}$`), `<INVITE>` 16 (`^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{16}$`). The key decides the kind: an invite under `c=`, a pairing code under `i=`, both keys, or anything appended is refused. The path is exactly `/pair`; there is no query.
- `ashidori://pair?server=<url>&c=<CODE>` or `ashidori://pair?server=<url>&i=<INVITE>`: the fallback the `/pair` page offers. `server` is percent-encoded `scheme://host[:port]` (no user info, path, query or fragment; `https` only, `http` also in debug builds); exactly `server` and one of `c` or `i`, each once, in any order.
- **The recovery code is never part of a link or a QR code.** A pairing link only admits a device to the code's user; the device still needs the recovery code, entered separately, to read anything. An invite link admits nothing until its user is created, and that user's key is created on that device.

The app parses both forms strictly and refuses anything else, whether the link was opened or scanned from a QR code on the sync setup screen (CameraX and ZXing, on the device; the camera permission is asked for when a scan starts). A link only fills in the server and the code on the sync setup screen, which names what happens (「新しく登録」 for an invite, 「この端末を追加」 for a pairing code) and shows the server; the user checks it and confirms (for an invite, after choosing a username). A link for a server other than the one already entered or configured needs a confirmation that names that server. An already registered device ignores links. The app has no built-in server: a build may set one with the Gradle property `ashidori.defaultServer`, which fills in the URL field and declares the App Link for that host.

Server side:

- `GET /pair` is a static page (CSP with a per-response nonce, no `connect-src`, as the admin page). It reads the fragment in the browser and accepts exactly the two forms above. It shows the server, the pairing code or 「招待」, the QR code of `<origin>/pair#c=<CODE>` or `#i=<INVITE>` (so that a page opened on a PC can be scanned by the app), and 「アプリで開く」, the `ashidori://` form built from `location.origin`. The QR encoder (qrcode-generator 2.0.4, MIT) is inlined as a nonce script, as on the admin page. It also says where to get the app (GitHub releases), and for a pairing code that the URL and the code can be entered by hand (an invite is used through its link or QR code).
- `GET /.well-known/assetlinks.json` is the Digital Asset Links statement that lets the app handle `https://<server>/pair` (Android App Links, `autoVerify`): `delegate_permission/common.handle_all_urls` for the package `net.sksat.ashidori` with the SHA-256 fingerprints of the app's signing certificates from the variable `ANDROID_CERT_SHA256` (comma-separated). Empty gives `[]`; a malformed value also gives `[]` (logged), and the deploy workflow refuses it. The app declares the App Link only for the host set at build time (`ashidori.defaultServer`), and none without it; other links open the `/pair` page in the browser.

## HTTP API

All bodies are JSON unless noted. Errors are `{"error": "..."}` with a suitable status.

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/admin/invites` | admin | the 200 newest invites: `[{id, status: "pending" \| "used" \| "expired" \| "revoked", createdBy, createdAt, expiresAt, usedAt, user: {id, username} \| null, revokedAt}]` (status by D1's clock) |
| POST | `/admin/invites` | admin | `{ttlDays?}` (1–30, default 7) → `201 {id, code, expiresAt, link}`, `link` = `https://<server>/pair#i=<code>`; the code is returned only here |
| POST | `/admin/invites/:id/revoke` | admin | revoke an unused invite (again: no-op) → `{revoked: true}`; `409 {"error": "invite already used"}` |
| GET | `/admin/users` | admin | list users: `{id, username, legacyName, email, status, createdAt, createdBy, disabledAt}` (`legacyName`: the earlier name of a legacy user without a username, else null; `createdBy`: `"self"`, `"invite:<id>"`, or a legacy admin's e-mail address) with usage (`deviceCount`, `lastSeenAt`, `storageBytes`, `batchCount`, `sharedCheckinCount`) |
| PATCH | `/admin/users/:id` | admin | `{username}` → the user: sets the username of a legacy user, or changes one; `409 {"error": "username taken"}` |
| POST | `/admin/users/:id/pairing` | admin | → `{code, expiresAt}` for a device of that user (for example a legacy user without one) |
| POST | `/admin/users/:id/disable`, `/admin/users/:id/enable` | admin | disable / enable a user |
| GET | `/admin/devices` | admin | list all devices with their user (`userId`, `username`) |
| POST | `/admin/devices/:id/revoke` | admin | revoke any device (and delete its unused codes) |
| GET | `/admin/checkins` | admin | the 200 most recently changed shared check-ins (moderation) |
| POST | `/admin/checkins/:id/delete` | admin | remove a shared check-in for good (leaves a tombstone) |
| GET | `/v1/info` | – | `{registration: "admin" \| "open", basemap: ["<version>", ...]}` |
| GET | `/basemap/:version/:file` | – | the app's offline basemap archive or its descriptor (see below) |
| POST | `/v1/accounts` | – | `{token, username, invite?, deviceName?}` → `201 {deviceId, username}` (retry: `200`, also when registration is closed or full, or the invite expired); creates a user with this device (see [Registration and limits](#registration-and-limits)). `400 {"error": "invalid username"}`; `409 {"error": "username taken"}`; with `invite`: `401 {"error": "invalid or expired invite"}`; without: `403 {"error": "registration closed"}` unless open, `403 {"error": "registration full"}` at `MAX_USERS`, `503` without the `RL_ACCOUNT` binding |
| POST | `/v1/devices/register` | – | `{code, name, token}` → `201 {deviceId}` (retry: `200`, also when rate limited or at the device limit); the code is consumed. `401` for an invalid code; `409 {"error": "too many devices"}` at the user's device limit (the code is then not consumed) |
| POST | `/v1/pairing` | device | → `{code, expiresAt}` for the device's user |
| GET | `/v1/devices` | device, viewer | the user's devices: `[{id, name, createdAt, lastSeenAt, revoked, kind, expiresAt}]` (`kind` `"device"` or `"viewer"`; `expiresAt` null for apps) |
| GET | `/v1/me` | device | the device's user: `{userId, username}` (both null for a legacy device; `username` null for a legacy user without one) |
| POST | `/v1/devices/:id/revoke` | device; viewer (self only) | revoke one of the user's devices (including self); also aborts the device's unfinished browser pairings and revokes the viewers it approved |
| PUT | `/v1/batches/:stream/:seq` | device | body: blob (`application/octet-stream`, 32 bytes to 8 MiB) |
| GET | `/v1/batches?after=<cursor>&limit=<n>` | device, viewer | metadata of the batches of all the user's devices, ordered by a global cursor |
| GET | `/v1/batches/:deviceId/:stream/:seq` | device, viewer | blob of one of the user's devices |
| POST | `/v1/viewer-pairings` | – | browser: `{commit, tokenHash}` → `201 {id, code, expiresAt}` (see [Browser viewers](#browser-viewers)) |
| GET | `/v1/viewer-pairings/:id` | browser token `T` | → `{state, expiresAt, nA?, scope?, ttl?, grant?, deviceId?, viewerExpiresAt?}` |
| POST | `/v1/viewer-pairings/:id/reveal`, `/confirm` | `T` | `{pkB, nB}` → `200`; confirm → `200 {state}`, or `409 {"error": "too many viewers"}` when approved and at the viewer cap |
| POST | `/v1/viewer-pairings/claim` | device | `{code}` → `{id, commit, expiresAt}` |
| POST | `/v1/viewer-pairings/:id/nonce`, `/approve` | the claiming device | `{nA, scope, ttl}` → `200`; `{grant}` → `200 {state, deviceId?}` |
| GET | `/v1/viewer-pairings/:id/app` | the claiming device | → `{state, expiresAt, confirmed, pkB?, nB?, deviceId?}` |
| POST | `/v1/viewer-pairings/:id/abort` | `T` or the claiming device | → `200 {state}` |
| PUT | `/v1/checkins/shared/:localId` | user device | share or update a check-in (see below) → `{id, cursor}` |
| DELETE | `/v1/checkins/shared/:localId?rev=<rev>` | user device | unshare this device's check-in (also one not shared yet) → `{deleted: true}`; `409` stale; `400` without `rev` |
| DELETE | `/v1/checkins/shared/by-id/:id?rev=<rev>` | user device | unshare any of the user's check-ins by its server id → `{deleted: true}`; `409` stale; `400` without `rev` |
| GET | `/v1/checkins/feed?after=<cursor>&limit=<n>` | user device | own and followed users' shared check-ins |
| GET | `/v1/users/lookup?username=<username>` | user device | → `{id, username}` of the active user with exactly that username, else `404` |
| POST | `/v1/follows` | user device | `{userId}` → `{user: {id, username}, status}`; sends a follow request |
| GET | `/v1/follows` | user device | users I follow or asked to: `[{id, username, status, since}]` |
| DELETE | `/v1/follows/:userId` | user device | unfollow, or cancel a request |
| GET | `/v1/follow-requests` | user device | incoming requests: `[{id, username, since}]` |
| POST | `/v1/follow-requests/:userId/accept` | user device | accept a request |
| POST | `/v1/follow-requests/:userId/decline` | user device | decline a request (deletes it) |
| GET | `/v1/followers` | user device | accepted followers: `[{id, username, since}]` |
| DELETE | `/v1/followers/:userId` | user device | remove a follower |
| GET | `/v1/health` | – | `{ok: true}` |
| GET | `/pair` | – | HTML page for a registration link, `#c=` or `#i=` (see [Registration links](#registration-links)); the code stays in the fragment |
| GET | `/.well-known/assetlinks.json` | – | App Links statement from `ANDROID_CERT_SHA256` (`[]` when empty) |

- "device" means an app device. A viewer token gets `403 {"error": "read-only token"}` on every route not marked "viewer" (see [Browser viewers](#browser-viewers)).
- "user device" means a device that belongs to a user. Legacy devices without a user get `403 {"error": "no user"}`.
- Listings, fetches and revocations only cover the caller's user. A device of another user is reported like a missing one (`404`).
- The numbers that the server and the app must agree on (the digits of a cursor and of a seq, page limits, blob sizes, the token length, the pairing-code TTL, the commit deadline `D` and the orphan grace period `G`) are listed in [`protocol-constants.json`](protocol-constants.json). The server's tests check the server against it, and that `D ≤ G / 2` (see [Batches](#batches)); they also check the app's cursor and seq bounds.
- While a limit variable of the server is invalid, requests that would create something (a user, also with an invite, a device, a batch, a shared check-in or its tombstone) get `503 {"error": "server misconfigured"}`. Everything else keeps working, including unsharing an existing entry.

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

A user is always created by their first app device, with `POST /v1/accounts`. The server variable `REGISTRATION` decides whether that needs an invite:

- `admin` (default): only with an invite, which an admin creates on the admin page. Any value other than exactly `open` (case and spaces matter), including an empty one, means `admin`.
- `open`: with an invite, or without one (self-registration). The server is then also an end-to-end encrypted backup and sync target for anyone; no admin is involved.

`GET /v1/info` tells the app which mode the server is in, so that it can offer the right setup steps. A server without this endpoint is treated as `admin`.

`basemap` lists the versions of the app's offline basemap that the server serves at `GET /basemap/<version>/ashidori-basemap-<version>.zip` (and `.json`, the descriptor). Older servers lack the field; the app then downloads from GitHub Releases. The route is public and unauthenticated (the archive is the same for everyone and covers whole countries), serves only those two names of a well-formed version (`404` otherwise and when the object is missing), supports a single `Range` (`206`, `416`), `If-Range`, `ETag`/`If-None-Match` and `Content-Length`, and sets no cookies. The app verifies the archive against the size and sha256 it pins, whichever source it came from. The web viewer never reads it with ranges, which would tell the server which area is shown: when the user chooses 「オフライン地図」 (the default is an online map), it downloads the whole archive once, checks it against the same pin, keeps it in the browser's Cache Storage and reads it locally.

`POST /v1/accounts {token, username, invite?, deviceName?}`:

- Creates a user named `username` and registers the calling device, authenticated by `token`, as its first device, in one transaction; with `invite`, consuming the invite in the same transaction. See [Registration requests](#registration-requests) for retries.
- `username` is required (see Usernames under [Authentication](#authentication)); a username another user has gets `409 {"error": "username taken"}`, and nothing changes (the invite stays unused). The device's name is the trimmed `deviceName` (1–64 characters, no control characters; else `400`), `"device"` without one.
- With `invite`: works in any `REGISTRATION` mode, is rate limited like registration with a pairing code, and is not limited by `MAX_USERS` (the admin decided to invite). The user is marked as invited (`createdBy: "invite:<invite id>"` in the admin user list).
- Without: only when `REGISTRATION` is `open`. Refused with `403 {"error": "registration full"}` once the server has `MAX_USERS` users (all users count, invited ones too). The user is marked as self-registered (`createdBy: "self"`). A self-registered user that has stored nothing (no batch and no shared check-in) 7 days after its creation is deleted by a scheduled job, with its devices, pairing codes and follows. Invited users and users that stored anything are never deleted automatically.
- Admins can disable any user.
- The app then creates `K` and shows the recovery code, as for any first device. Further devices pair from this device (`POST /v1/pairing`) and enter the recovery code.

Unused invites are deleted by a scheduled job 30 days after they expired or were revoked; used ones are kept.

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
| `MAX_USERS` | 100 | users on the server, for self-registration (registration without an invite) |
| `MAX_TOTAL_BYTES` | 50 GiB | blob bytes on the server |

- Per-user limits count all the user's devices, revoked ones included (their data is kept). Legacy devices without a user share their data, so together they count as one user.
- Usage is kept in counters that change in the same statement as the data, so a check reads one row instead of summing the user's batches.
- The admin user list shows each user's usage; the admin page also shows the server totals.
- Clients treat `quota exceeded` and `server storage full` as persistent: they show it to the user and do not retry with backoff; the next scheduled run tries again (for example after the admin raised the limit).

## Browser viewers

A browser can read the user's batches as a read-only **viewer**, after the user opted in with 「ブラウザでも見る」 in the app. The protocol, its values and its races are specified in [`design/browser-pairing-v1.md`](design/browser-pairing-v1.md); this section summarizes what the server and the clients must keep.

- **What the browser receives**: the batch key only, sealed with HPKE (RFC 9180 base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM) to a temporary X25519 key the browser made, after the user compared four words (BIP-39 Japanese, from a transcript of every field of the attempt) on both sides, approved in the app and confirmed in the browser. Never `K`, the recovery code or the blob authentication key. The browser keeps the key in memory only, as a non-extractable `CryptoKey`; a reload starts a new pairing.
- **Consequence the user accepts**: the batch key does not change, so the browser can read all past batches and every later batch sealed with it. The viewer's expiry only stops the server from serving; a key copied out stays usable after it. The app shows this on the approval screen.
- **Attempts** (`/v1/viewer-pairings`): the browser creates one with a commit `c` = H(pkB ‖ nB) and the SHA-256 of its token `T`, and shows the 8-character code; the app claims it with the code, sends `nA` = H(sA) with the scope (`batch-v1`) and the lifetime (3600 or 86400 s); only then does the browser reveal `pkB` and `nB`. States: `open`, `claimed`, `nonce`, `revealed`, `active`, `aborted`. An attempt expires 10 minutes after its creation (D1's clock); every transition towards `active` checks that in its own statement (`410` after it). Every transition is one conditional statement or batch, so of two concurrent ones exactly one wins.
- **Activation**: the later of the app's approval (with the grant) and the browser's confirmation creates the viewer, in the same transaction, only while the approving device is not revoked, the user is active, and the user has fewer than 5 active viewers (`409 {"error": "too many viewers"}` otherwise, to the approval or the confirmation, whichever came last; the attempt stays `revealed`, and either side can send its step again once a slot is free). A re-sent grant must be byte-identical (`409` otherwise); a confirmation after activation answers `200`. `GET /v1/viewer-pairings/:id` answers from the stored state, also after the attempt expired, while the viewer is valid (result recovery). Attempt rows are deleted a day after they expired.
- **Viewer devices**: `kind` `viewer`, the name 「ブラウザ」, an expiry (`expiresAt`), and the approving device (`granted_by`). The token is `T`; its hash is stored as `v:` + SHA-256(`T`), so a server version without viewers never authenticates it. Viewers do not count towards `USER_MAX_DEVICES`. An expired or revoked viewer gets `401`. A scheduled job deletes viewers 30 days after they expired or were revoked (they own no batches).
- **Scope (default deny)**: a viewer may call `GET /v1/devices`, `GET /v1/batches`, `GET /v1/batches/:deviceId/:stream/:seq`, its own attempt (`GET /v1/viewer-pairings/:id`), and `POST /v1/devices/:id/revoke` for itself (the browser's 「登録を解除」). Every other route answers `403 {"error": "read-only token"}`: uploads, pairing codes, claims, shared check-ins, follows, `/v1/me`.
- **Revocation cascade**: revoking a device (by the app or an admin) aborts the unfinished attempts it claimed and revokes the viewers it approved, in the same transaction; a database trigger (migration 0008) does so for any update that revokes the device, also by an older server version. Activation checks the approving device again, and so do the viewer's authentication and the grant retrieval (an unrevoked app device of the same user), so a missed cascade keeps no access. Either order leaves no viewer approved by a revoked device. A disabled user's viewers get `401`.
- **Rate limits**: creating an attempt 10 per 10 minutes per IP; claiming 20 per 10 minutes per IP and per user (wrong codes count). Polling (every 2 seconds) has only the edge limit.
- **What the server sees**: the code, `c`, `nA`, the scope and lifetime, `pkB`, `nB`, the grant (sealed), the approving device, and the viewer's fetches. It does not see `sA` or the batch key, unless the page itself is changed: the page is served by the same Worker, and a changed page could send the key away. That is why the browser view is opt-in.

## Shared check-ins and follows

A user can share selected check-ins with their followers. Shared check-ins are
**plaintext on the server**, by definition: the user chose to show them to
other people. Everything else stays end-to-end encrypted in batches.

### Follows

- Users find each other by **username** (see Usernames under [Authentication](#authentication)). A legacy user without one cannot be looked up until an admin sets it.
- There is no user directory and no search. `GET /v1/users/lookup` matches the exact username only and is rate limited, which limits bulk probing of usernames. It does not prevent guessing: whoever tries a username that exists learns that it exists.
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
- `DELETE /v1/checkins/shared/:localId?rev=<rev>` unshares this device's entry: it becomes a tombstone with `rev`, and the server deletes the content (time, position, venue, category, place, comment).
  - `rev` is required, as for PUT: a DELETE without it gets `400 {"error": "rev is required"}`. The client sends the same `rev` on every retry of one unshare. (A `rev` that the server assigned would differ on each retry, so a retried DELETE would be a new, later operation and could unshare an entry that was shared again meanwhile.)
  - Unsharing a tombstone again with a higher `rev` only raises the `rev`; the cursor stays.
  - An unknown `localId` gets a tombstone with `rev` as well, so that a first PUT that arrives after the DELETE with a lower `rev` is refused as stale. It counts against `USER_MAX_SHARED_CHECKINS` (`507` at the limit). Followers see it as a deleted entry they never saw shared.
- `DELETE /v1/checkins/shared/by-id/:id?rev=<rev>` does the same by the server `id` (`rev` required likewise), from any device of the entry's user, for example after the device that shared it was lost and revoked. An unknown id, or another user's entry, is `404`.
- **Removal by an admin is final.** The entry becomes a tombstone regardless of its `rev`. Its owner cannot share it again under the same `localId`, whatever `rev` it sends (`409 {"error": "removed by an admin"}`). An owner's DELETE of it returns `200` and changes nothing. The owner can still share the check-in anew under another local id. This applies to entries removed after this rule was introduced; earlier removals behave like an unshare.
- **Sending a shared check-in is sending a position to an external service.** Unlike the sync streams, it is plaintext: the server operator and the followers read its time, position and venue. The app sends one only on an explicit share action for that check-in, where the share UI says who can read it, whatever 「オンライン地図と地名検索」 says; it never shares automatically. (The app has no share UI yet.)
- Every entry has `visibility`. It is always `followers` for now. Sharing with selected users, encrypted per recipient with public keys, is expected to add other values without changing the API above.

### Feed

`GET /v1/checkins/feed?after=<cursor>&limit=<n ≤ 500>` returns the caller's own
entries and those of the users the caller follows (accepted), ordered by cursor:

```json
{
  "items": [{"id": "...", "cursor": 12, "user": {"id": "...", "username": "..."},
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
  - users: username (and, for users from before usernames, their earlier name and an optional e-mail address), status, creation time, how the user was created (an invite, which records the user it created, or self-registration), and usage counters;
  - invites: hash of the code, creating admin, creation, expiry, use and revocation times;
  - devices: id, name, registration time, last-seen time (rounded down to the hour); for viewers also the expiry and the approving device;
  - browser pairing attempts (deleted a day after they expired): the code's hash, the public values of the attempt, the sealed grant, the approving device, and the times of each step;
  - batches in D1: device, stream name, sequence number, blob size, SHA-256, upload time (rounded down to the hour);
  - blobs in R2: the object key (device, stream, sequence number, SHA-256) and the **exact** upload time that R2 records for every object. The hour rounding applies to D1 only.
  - follows: who follows whom, with request and acceptance times (rounded down to the hour);
  - shared check-ins: **in plaintext**, as shared, with the hour of the last change and the client's `rev` (which may be the exact change time). Unshared entries keep only a tombstone and its `rev`.
- **Residual leaks**:
  - The number, size and upload times of batches per stream hint at activity, e.g. that the user was moving (more data), or that a device was offline.
  - Stream names and archive file names inside the ciphertext are hidden, but the stream name `archive` and the batch sizes tell roughly how much raw logging a user does.
  - The batch cursor is global. A user can see from the gaps between their own cursors roughly how many batches all other users uploaded meanwhile.
  - The blob format (the magic, `ASH1` or `ASHM`) is visible, so the server can tell roughly when each device was updated to v1.1, and a batch of the stream `authcut` tells that the user enabled browser viewing.
  - Cloudflare sees client IP addresses and request times at its edge. The Worker does not store IPs except in rate-limit counters, which a scheduled job deletes within about 10 minutes after their window has ended; it does not log IPs or request bodies, and per-request invocation logs are disabled.
- **The operator controls the server code.** A modified server could log more metadata (IPs, exact times). It still cannot decrypt batches, because `K` never reaches the server.
- **The key must only be used in the app.** A web page served by the sync server (or from any origin the operator controls) must never ask for `K` or the recovery code, nor decrypt batches with it: the operator could change that page to exfiltrate the key, and the end-to-end encryption would be void.
  - The one exception is the batch key, handed to a browser only after the user opted in and approved that browser ([Browser viewers](#browser-viewers)). For a user who did, an operator who changes the page can obtain the batch key and read all batches sealed with it, past and future; revoking the viewer does not undo that. Users who never opt in are not affected.
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
    - A correction applies to every leg that it overlaps by more than half of the leg. Legs are rebuilt from the record each time, so when their boundaries change (new fixes, a changed classifier), a saved correction applies to the new legs that overlap its range by more than half, and no longer to the others.
    - Of several corrections for one leg, the first in this order wins, in the app and in the web viewer alike: `updatedAt` descending, then the device id descending, then the device's change counter descending (the order its rows are uploaded in). This is last-writer-wins by the devices' clocks: a device whose clock is off can make an older correction win. Known limitation.
    - 取り消し (undo) in the app uploads a new row with the inferred mode instead of deleting one (deleted rows are not uploaded). A correction to the inferred mode leaves the leg as built (not marked as edited).
  - Place edits are per device. A device uploads its own places, and nothing receives another device's edits of a place, so edits of the same place on two devices do not converge. The app's undo of a place edit is local: it restores the field it changed only while that field still holds the value it set on that device.
- **Device status** (stream `status`): one record per successful small-stream sync run (hourly job and "sync now"), uploaded after the other small streams:
  `{t, battery:{pct, charging}, recording:{on, mode, since}, lastFix:{t, lat, lon, acc}|null, app:{versionName, versionCode}, device:{model}}`
  - `mode` is the logging mode key (`eco`, `balanced`, `precise`, `max`); `since` is when recording was switched on (0 if off or unknown).
  - `lat`/`lon` are rounded to 1e-6°, `acc` (metres, may be null) to 0.1 m.
  - `authcut: {deviceId, seq, sha256}`: the cut-over record `C` the device knows (v1.1; omitted while it knows none). See [Blob authentication](#blob-authentication-v11).
- **Cut-over record** (stream `authcut`): normally one per user (two apps approving a first browser at the same time can make two; any one that verifies serves): `{deviceId, stream, below, digest}` per line. See [Blob authentication](#blob-authentication-v11).
- **Archive** (stream `archive`): the logged signals are not uploaded row by row. The device keeps the current day in SQLite; once a day is over, it writes each table of that day to a Parquet file `<table>/YYYY-MM-DD.parquet`, and once a month is over, it merges the month's day files into `<table>/YYYY-MM.parquet`. Since app 0.21 the raw signal tables (`gnss_raw`, `gnss_sat`, `gnss_nav`, `nmea`, `imu`) are no longer merged into months: they stay as day files (earlier versions may have sent month files of them). Each such file is uploaded once, split into chunks of at most 3 MiB, one batch per chunk:
  `{file: "<table>/<YYYY-MM-DD or YYYY-MM>.parquet", gen, part, parts, data}`
  - `data` is base64 of the chunk; concatenating `part` 0 to `parts - 1` of one (`file`, `gen`) gives the Parquet file.
  - `gen` identifies the file version (hex, derived from the size, device, inode and modification time of the open file). If a file changes while it is being uploaded, it is sent again from part 0 under a new `gen` and new seqs. A reader assembles parts per (`file`, `gen`) and uses a complete generation.
  - The device opens the file once per upload run and takes its size (`fstat`), its `gen` and every part from that one open descriptor. Archive files are only ever replaced by an atomic rename of a new file, never rewritten in place, so the descriptor keeps reading the version it opened, also after the path names a newer version or is deleted (merged into a month). Progress (the generation, the next part and the bytes enqueued) is committed with each part; a later run resumes only if the file it opens is the same generation. The file counts as uploaded only with its last part, and only if the parts add up to that generation's size (formal/tla `Outbox_archive`; review R1). A file that an earlier app version had partly sent (its `gen` was derived from the path's size and modification time) is sent again from part 0.
  - Each part is a separate batch, committed to the outbox and sent on its own.
  - A file is uploaded again whenever it changed since it was handed over (a new `gen`): a day archived in portions, or late rows merged into a day already sent. The device does not upload a day file while the day is still being archived. Files that app versions before 0.21 handed over are uploaded once more (their mark names no version). A restorer combines all complete generations and the day and month files of a table and drops exact duplicate rows.
  - Tables and columns (the first column is the time in ms, except where noted). Columns are only ever added, at the end of a table. Files written since archive schema 3 carry `ashidori.table` and `ashidori.schema` in the Parquet footer's key-value metadata; earlier files have none and are one of the table's earlier layouts. A reader matches columns by name and takes a column a file lacks as null; a column of the expected name with another type is an error:
    - `fixes`: `time, lat, lon, acc, alt, speed, bearing, provider, alt_acc, msl, msl_acc`. `alt` is the WGS84 ellipsoidal height and `alt_acc` its accuracy; `msl` is the altitude above mean sea level (Android's geoid model, Android 14+) and `msl_acc` its accuracy, all in metres. The last three were added in archive schema 3 (after app 0.21.0); older files lack them, and they are null where unknown.
    - `motion`: `start, end, std, mean, steps`
    - `battery`: `time, pct, charging, screen_on, mode, charge_uah, current_avg_ua, current_now_ua, voltage_mv, temp_c, plug`. The columns after `mode` were added in archive schema 2 (after app 0.21.0); older files have only the first five. `charge_uah` is BATTERY_PROPERTY_CHARGE_COUNTER. The currents are discharge-positive µA (Android reports positive into the battery; the app stores the negated value). `temp_c` is °C, `plug` is `none`, `ac`, `usb`, `wireless` or `dock`. A value the device does not provide is null.
    - `gnss_status`: `time, visible, used, top4, l5, env, acc`
    - `energy_state` (archive schema 2): `start, end, gnss, gnss_interval_ms, provider, detail, still, screen_on, charging, mode, eff_mode, motion, env, end_unknown`. One row per stretch of unchanged logger state, split at local midnight; the first column is `start` (ms). `gnss` is 1 when the GNSS engine is requested, `gnss_interval_ms` the requested location interval (ms), `provider` the registered provider, `detail` a running raw-data log, `still` still mode, `charging` plugged in or charging; `mode` and `eff_mode` the chosen and the effective logging mode; `motion` and `env` the motion and indoor/outdoor estimates. `end_unknown` 1: the state was last confirmed at `end` and its real end is not known (the app was stopped without closing it).
    - `pressure` (archive schema 3): `time, hpa`. Barometric pressure in hPa as the sensor gives it (no correction): the median of each 5 s while moving; while still, the median of each minute, kept only when it moved 0.05 hPa or more from the last row or 10 minutes passed. Readings the sensor did not deliver (its buffer overwritten while the device slept) are missing: a gap is a gap.
    - `gnss_raw`: the columns of a GnssLogger `Raw` line
    - `gnss_sat`: `UnixTimeMillis, SignalCount, SignalIndex, ConstellationType, Svid, CarrierFrequencyHz, Cn0DbHz, AzimuthDegrees, ElevationDegrees, UsedInFix, HasAlmanacData, HasEphemerisData, BasebandCn0DbHz`
    - `gnss_nav`: `time, Svid, Type, Status, MessageId, SubmessageId, Data` (`time` is the preceding record's time)
    - `nmea`: `time, message`
    - `imu`: `time, elapsedRealtimeNanos, sensor (Accel, Gyro or Mag), x, y, z`
    - `gnss_fix` (since app 0.21): the columns of a GnssLogger `Fix` line, `UnixTimeMillis` first (earlier versions did not archive these lines)
  - The small-stream job uploads `fixes`, `motion`, `battery`, `gnss_status`, `energy_state` and `pressure`; the raw-log job uploads `gnss_raw`, `gnss_sat`, `gnss_nav`, `nmea` and `imu`. A reader ignores a table it does not use (the web viewer reads `fixes` and `motion`).
  - A range the user deleted on the device is cut out of its archive files there (a new generation of each, sent again); a file is not uploaded while such a cut is pending. Generations sent before stay on the server, and a restorer combining all generations would bring the deleted rows back: deletions are not synced yet (see docs/design/energy-elevation.md).
- Seqs of a stream can have gaps (see the upgrade rule under "Upload outbox on the device").
- Earlier app versions uploaded raw GNSS logs as stream `gnss` (`{file, part, parts, data}` with chunks of the GnssLogger text file) and the signal tables as JSON Lines streams `fixes`, `motion` and `battery`. Servers may still hold such batches.

### What a backup can restore

- Editable data (places, check-ins, overrides) up to the last successful sync, except deletions.
- Logged signals of the days that were archived and uploaded. The current day is not uploaded until it is archived after midnight, so a device lost during the day loses that day's positions, motion and battery records, even right after a successful sync.
- Rows that arrive for a day after its file was uploaded are merged into the device's file but not uploaded again (the uploaded mark is per file name, not per generation; formal/tla `Outbox_late`).
- The app does not restore from the server yet. It only downloads the latest `status` of the user's other devices (below). The formats above are what a restorer will read.

## Other devices' status (download)

Each small-stream job (hourly, and "sync now") downloads the latest status of the user's other devices after its upload, whether or not the upload succeeded (a full quota does not stop it); only a stopped job skips it. It runs under the same sync lock, and failures here never fail or retry the job.

- It refreshes `GET /v1/devices` (names, revocations).
- It walks `GET /v1/batches?after=<cursor>&limit=1000` from a cursor kept on the device, at most 20 pages per run. The cursor is only a fetch position and only moves to cursors of rows actually listed.
- Every number of a listing row must be an integer in the API's range: `cursor` at most 15 decimal digits (what `after` accepts), `seq` at most 12 digits, `size` non-negative. A row outside it is malformed and fails its page, so the stored cursor can always be sent back as `after`. A stored cursor or rescan position outside that range (kept by an older version) is dropped, and the walk starts again from `after=0`.
- The normal walk, the rescan (below) and the fetches do not depend on each other: a failing walk (a refused cursor, a malformed row) neither stops the rescan nor the fetches of the run, and a failure other than a network error wants a rescan. When a rescan is due, the normal walk leaves 5 of the 20 pages to it.
- The cursor does not move past a row whose cursor is not above the previous one (the page's first row: above `after`), nor, on a page with fewer than `limit` rows (the end of the listing), past a row whose cursor is more than 10,000,000 above the previous one. The rows of such a page are still taken as candidates, the page is re-listed on the next run, and a rescan is wanted. A full page is always followed, since an honest gap at its start would otherwise stop the walk for good. (The cursor is shared by all users, so honest gaps exist, but they stay far below that bound.)
- Per other device it keeps candidates: listed `status` rows above the highest seq that decrypted and parsed, each with its seq, size, SHA-256 and failure count. Two rows with the same seq but another size or SHA-256 are two candidates. It keeps 4 still to try and, separately, the newest 4 exhausted ones (below), so exhausted rows neither crowd out the others nor come back as new when listed again.
- **Rotation.** The candidates still to try are the newest ones, except that a row whose seq lies within the seqs at which the device's candidates were exhausted in the current *pass* (`sweep`: lowest to highest such seq) ranks below every other row, both for a kept place and as the fetch target. Exhausted rows that fell out of the kept 4 and come back on a rescan therefore no longer take the places of rows not tried yet: each rescan moves the tried rows further down the listing until every listed seq has been tried. A row above the pass (a new status) ranks as usual. A pass ends at the end of a rescan that left the device no row outside it to try; the next pass starts again from the newest. For example, with a genuine seq 0 and made-up seqs 1–8 listed on one page, seqs 8–5 are exhausted first, the next rescan brings 4–1, and the one after that 0, which is read.
- It fetches one blob per device and run, that of the first candidate (in the order above) that is not exhausted (`GET /v1/batches/:deviceId/status/:seq`), checks that its size and SHA-256 match the listing (rows without `size` and `sha256` are malformed and fail the page), and opens it with the AAD of the source device, `"{deviceId}/status/{seq}"`, using the last record: an ASHM blob after checking its mac, an ASH1 blob only as the cut-over rules allow (see [Blob authentication](#blob-authentication-v11)). Blobs of other streams are not downloaded, except a cut-over record `C` named by an accepted status or listed, while none is verified (at most 4 tries per run).
- A network error changes nothing, so the next run tries again. A status that cannot be read (size or hash mismatch, decrypt or parse failure, `404`, over-size) is *unresolved*: its candidate is retried up to 3 times and is then *exhausted*, and the next newest candidate is tried. The newest unresolved status is shown on the device's row until a status of that device at least as new is read. It is never skipped silently, and the read seq only increases on success.
- An exhausted candidate is not given up for good: it is retried 1 hour after its third failure, and after each further failure the wait doubles, up to a day. These retries come from their own budget, at most one per device and 4 per run (those due longest first), next to the normal fetch, so rows a server made up cannot take the normal fetches, and a genuine status that a server failed a few times (e.g. three `404`s) is read once the server serves it (formal/tla `StatusDownload_hostile`, `StatusDownload_r5`; review R5). The 更新 button of the 端末 section makes every exhausted candidate due at once. The day is a cap on the backoff, not on the time until the next try: the budget of 4 retries per run is shared by all devices, so with many exhausted candidates a due one can wait several runs longer.
- **Rescan.** Now and then the device walks the listing again from `after=0`, with the same page budget per run (shared with the normal walk, which leaves it 5 pages), continuing on later runs. A rescan starts 7 days after the previous one, or 1 day after it when one is wanted (a page broke the cursor rule above, the normal walk failed other than by a network error, or a device has no candidate left to try). When it reaches the end of the listing, or a page does not move it on, the cursor becomes where it ended. A row the server made up (a huge seq or a huge cursor) therefore delays, but does not stop, the download of later statuses once the server stops listing it, and a made-up seq is passed over by the older candidates even while it is listed (formal/tla `StatusDownload_fixed`, `StatusDownload_fixed1`; review #7). Without the rotation above, more made-up rows than candidates are kept per device (4 in the app) crowd out the genuine status for as long as the server lists them, whatever the run intervals (formal/tla `StatusDownload_manyfakes_pre`, `StatusRotation_f6pre`; review F6: a genuine seq 0 and made-up seqs 1–8 on one page repeat the same state after every rescan, with hourly fetches and daily rescans). With the rotation, every listed seq is tried, and the genuine status read, for a fixed set of listed rows (formal/tla `StatusDownload_manyfakes`, `StatusRotation_*`; in the F6 example within 2 rescans, or 4 if the server also fails the genuine status 3 times, at the app's intervals). A server that keeps adding new made-up rows above the genuine one can still delay it for as long as it does.
- Size caps, enforced while reading: a listing page ≤ 1 MiB and ≤ `limit` rows; a status blob ≤ 64 KiB; decompressed status ≤ 64 KiB and ≤ 64 JSON lines; other JSON responses ≤ 1 MiB.
- The receiving device keeps the latest status per device locally. It resolves an area name for the position from the on-device index, and, only with 「オンライン地図と地名検索」 on, with the platform `Geocoder` where the index has none (it can contact a network geocoding provider). Nothing derived is uploaded to the sync server.

## Upload outbox on the device

- Building a batch atomically claims the next seq, stores the ciphertext in a local outbox table and records the source progress (the change counter for editable streams, the uploaded mark for archive files), in one SQLite transaction. Archive files are enqueued chunk by chunk: each chunk commits its own outbox row, seq and next-part mark, and the file is marked uploaded only after its last chunk.
- An outbox row is deleted only after `200` or `201`. A retry sends the identical bytes, so a seq is never reused for different ciphertext.
- Rows carry a class, `small` or `raw`; `raw` rows are only sent by the raw-log job, under its network conditions.
- All sync runs (periodic, "sync now", raw logs, the status download and unpairing) are serialized on the device.
- Editable streams are tracked by a device-local change counter, not `updatedAt` (equal timestamps or a clock moved back would skip rows). Records still carry `updatedAt`.
- Encryption happens outside the database transaction; the transaction then commits the outbox row, the seq increment and the progress mark, and refuses if the seq moved meanwhile.
- Archive enqueueing pauses while a class's unsent outbox exceeds 64 MiB.
- Upgrade from a version without the outbox: legacy pending blobs are discarded unsent and legacy progress marks are dropped, so all archive files and editable tables are uploaded again under new seqs above anything the old version used. Readers must tolerate duplicate files and rows. No ciphertext sealed by an older version is ever sent.
- Sync progress is kept per registration (server URL and device id). Unpairing cancels and waits for running jobs, then clears all progress and unsent batches, so a new registration uploads everything again and never sends ciphertext bound to the old device id.
- Unpairing clears several stores one after the other (registration, token, master key, sync progress, peer state). It first writes an *unpairing* mark to disk and removes it only after the last step. While the mark is set the device is neither paired nor ready and no new registration is saved; a crash in between is finished at the next start, from the first step again. A removal of the mark that failed and could not be read back is written again a few times; until it is known, the mark counts as set. A sync run reads the registration, device id, token and key in one read: an unpairing that marks itself (outside the serialization of sync runs) as a run starts gives that run either the whole registration or none (formal/tla `RegistrationClient_runread`).

## Network policy on the device

- **Small streams** (places, check-ins, overrides, the archives of fixes, motion, battery and GNSS status, and status): WorkManager periodic job, every hour, `NetworkType.CONNECTED`.
  - With "モバイル回線でも同期" off, the job requires `NetworkType.UNMETERED` instead.
- **Raw logs** (the archives of raw GNSS, satellite status, navigation messages, NMEA and IMU): a separate periodic job, every 6 h, requiring `NetworkType.UNMETERED`, and optionally charging.
- Both jobs use exponential backoff and resume where they stopped.
- `quota exceeded` and `server storage full` are not retried with backoff: the job ends, the error is shown in the sync settings, and the next periodic run tries again.

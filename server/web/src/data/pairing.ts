// The browser's side of pairing v1 (docs/design/browser-pairing-v1.md):
// the browser shows a code, the user enters it in the app's 「ブラウザでも見る」,
// both show the same four words, the user approves in the app and presses
// 「一致する」 here, and the browser receives the batch key sealed to its
// temporary key. It receives nothing else: never K, the recovery code or
// the blob authentication key.
//
// Rules this module keeps (the design's "固定する時点" and "クライアントの状態"):
// - pkB and nB are sent only after nA arrived (the commit c binds them before).
// - id and code are pinned at the create response; nA, scope and ttl at their
//   first receipt; a later different value aborts the attempt.
// - The grant is opened only after the user pressed 「一致する」, and only if
//   every check passes; then the raw key is imported as a non-extractable
//   AES-GCM key and zeroed, and the temporary key pair is dropped.
// - Expiry: 10 minutes from the create response by a monotonic clock. Past
//   it nothing is sent; one more GET tells whether the attempt became active.
// - Results of an attempt that ended (or was replaced) are discarded.
//
// Everything is in memory: nothing goes to localStorage, sessionStorage or
// IndexedDB, so a reload or a closed tab starts over.

import { fromBase64UrlExact, hex, toBase64Url } from "./bytes";
import { importBatchKey } from "./crypto";
import { CODE_RE, GRANT_LEN, ID_LEN, KEY_LEN, SCOPE, TTLS, commitOf, newKeyPair, openGrant, sasWords, sha256, transcriptOf } from "./paircrypto";
import type { ViewerSession } from "./session";

export const POLL_MS = 2000;
export const ATTEMPT_MS = 10 * 60 * 1000;
const DEVICE_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
const MAX_RESPONSE = 16 * 1024;

export type EndReason =
  /** The user pressed 「一致しない」. */
  | "mismatch"
  /** The app (or the server) ended the attempt. */
  | "aborted"
  | "expired"
  /** A check failed, the server broke the protocol, or a value changed. */
  | "failed"
  /** Replaced or closed by this page. */
  | "cancelled";

export type PairingEvent =
  | { kind: "code"; code: string }
  /** The app entered the code; the words follow once it sent its nonce. */
  | { kind: "claimed" }
  | { kind: "words"; words: string[]; scope: string; ttl: number }
  /** The user confirmed; waiting for the app's approval. */
  | { kind: "waiting" }
  | { kind: "done"; session: ViewerSession }
  | { kind: "ended"; reason: EndReason };

export interface PairingDeps {
  fetch: typeof fetch;
  /** Origin of the API; "" = same origin. */
  base: string;
  /** Monotonic milliseconds (performance.now). */
  now(): number;
  /** Wall-clock milliseconds, for the registration time shown. */
  wall(): number;
  sleep(ms: number): Promise<void>;
  random(n: number): Uint8Array;
  keyPair(): Promise<{ keys: CryptoKeyPair; pkB: Uint8Array }>;
  importKey(raw: Uint8Array): Promise<CryptoKey>;
}

export function browserDeps(): PairingDeps {
  return {
    fetch: fetch.bind(globalThis),
    base: "",
    now: () => performance.now(),
    wall: () => Date.now(),
    sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
    random: (n) => crypto.getRandomValues(new Uint8Array(n)),
    keyPair: newKeyPair,
    importKey: importBatchKey,
  };
}

/** Ends the run with a reason. */
class End extends Error {
  constructor(readonly reason: EndReason) {
    super(reason);
  }
}

interface Reply {
  status: number;
  body: Record<string, unknown>;
}

interface Pinned {
  nA: Uint8Array;
  scope: string;
  ttl: number;
}

export class PairingAttempt {
  private keys: CryptoKeyPair | null = null;
  private pkB: Uint8Array = new Uint8Array(0);
  private nB: Uint8Array = new Uint8Array(0);
  private c: Uint8Array = new Uint8Array(0);
  private token = "";
  private id = "";
  private idBytes: Uint8Array = new Uint8Array(0);
  private code = "";
  private deadline = Infinity;
  private pinned: Pinned | null = null;
  private th: Uint8Array | null = null;
  private grant: { bytes: Uint8Array; deviceId: string; expiresAt: number | null } | null = null;
  private confirmed = false;
  private confirmSent = false;
  private claimedSeen = false;
  private closed = false;
  private wake: (() => void) | null = null;

  constructor(
    private readonly deps: PairingDeps,
    private readonly onEvent: (e: PairingEvent) => void,
  ) {}

  /** Milliseconds left of the attempt (10 minutes from the create response). */
  remainingMs(): number {
    return Math.max(0, this.deadline - this.deps.now());
  }

  get ended(): boolean {
    return this.closed;
  }

  /** Runs the attempt to its end; every outcome is reported as an event. */
  async run(): Promise<void> {
    try {
      await this.create();
      await this.waitForNonce();
      await this.reveal();
      await this.showAndReceive();
    } catch (e) {
      const reason = e instanceof End ? e.reason : "failed";
      if (!this.closed && reason !== "cancelled" && reason !== "expired") await this.abortQuietly();
      this.finish(reason);
    }
  }

  /** 「一致する」: allows the grant to be opened. */
  confirm(): void {
    if (this.closed || !this.th || this.confirmed) return;
    this.confirmed = true;
    this.emit({ kind: "waiting" });
    this.wake?.();
  }

  /** 「一致しない」: aborts the attempt on the server and drops the keys. */
  async mismatch(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    this.wake?.();
    await this.abortQuietly();
    this.finish("mismatch", true);
  }

  /** Closes the attempt without an event (the page replaced it). */
  async cancel(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    this.wake?.();
    await this.abortQuietly();
    this.drop();
  }

  // ---- steps ----

  private async create(): Promise<void> {
    const { keys, pkB } = await this.deps.keyPair();
    if (pkB.byteLength !== KEY_LEN) throw new End("failed");
    this.keys = keys;
    this.pkB = pkB;
    this.nB = this.deps.random(KEY_LEN);
    this.c = await commitOf(this.pkB, this.nB);
    // A token already in use (409) is replaced once by a new one.
    for (let tries = 0; ; tries++) {
      this.token = toBase64Url(this.deps.random(32));
      const tokenHash = hex(await sha256(new TextEncoder().encode(this.token)));
      const r = await this.send("POST", "/v1/viewer-pairings", { commit: toBase64Url(this.c), tokenHash }, false);
      this.check();
      if (r.status === 409 && r.body.error === "token already in use" && tries === 0) continue;
      if (r.status !== 201) throw new End("failed");
      const idBytes = fromBase64UrlExact(r.body.id, ID_LEN);
      const code = r.body.code;
      if (!idBytes || typeof code !== "string" || !CODE_RE.test(code)) throw new End("failed");
      this.id = r.body.id as string;
      this.idBytes = idBytes;
      this.code = code;
      this.deadline = this.deps.now() + ATTEMPT_MS;
      this.emit({ kind: "code", code });
      return;
    }
  }

  /** Polls until nA arrives; pins nA, scope and ttl. pkB and nB are not sent before. */
  private async waitForNonce(): Promise<void> {
    for (;;) {
      const s = await this.poll();
      if (s && this.pinned) return;
      await this.pause();
    }
  }

  private async reveal(): Promise<void> {
    for (;;) {
      this.ensureTime();
      const r = await this.send("POST", `/v1/viewer-pairings/${this.id}/reveal`, { pkB: toBase64Url(this.pkB), nB: toBase64Url(this.nB) }, true);
      this.check();
      if (r.status === 200) break;
      if (r.status !== 0) this.refused(r);
      await this.pause();
    }
    const p = this.pinned!;
    this.th = await transcriptOf({ id: this.idBytes, code: this.code, c: this.c, nA: p.nA, scope: p.scope, ttl: p.ttl, pkB: this.pkB, nB: this.nB });
    const words = await sasWords(this.th);
    this.check();
    this.emit({ kind: "words", words, scope: p.scope, ttl: p.ttl });
  }

  /** Waits for the user's decision and the app's approval, then opens the grant. */
  private async showAndReceive(): Promise<void> {
    for (;;) {
      if (this.confirmed && !this.confirmSent && this.deps.now() < this.deadline) {
        const r = await this.send("POST", `/v1/viewer-pairings/${this.id}/confirm`, undefined, true);
        this.check();
        if (r.status === 200) this.confirmSent = true;
        else if (r.status !== 0) this.refused(r);
      }
      await this.poll();
      if (this.confirmed && this.grant) return this.receive();
      await this.pause();
    }
  }

  private async receive(): Promise<void> {
    const g = this.grant!;
    let raw: Uint8Array | null = null;
    try {
      raw = await openGrant(this.keys!, this.th!, this.pinned!.nA, g.bytes);
      const batchKey = await this.deps.importKey(raw);
      this.check();
      const session: ViewerSession = {
        token: this.token,
        batchKey,
        deviceId: g.deviceId,
        name: "このブラウザ",
        registeredAt: this.deps.wall(),
        expiresAt: g.expiresAt,
        storage: "tab",
        source: "pairing",
      };
      this.closed = true;
      this.drop();
      this.emit({ kind: "done", session }, true);
    } catch (e) {
      if (e instanceof End) throw e;
      // The key is not used: this browser's registration is ended at once.
      await this.revokeQuietly(g.deviceId);
      throw new End("failed");
    } finally {
      raw?.fill(0);
    }
  }

  // ---- polling ----

  /**
   * One GET of the attempt. Checks the pinned values and keeps the first
   * grant. Returns null on a network error. Past the deadline, this is the
   * one last GET: it ends the attempt unless the grant can be opened.
   */
  private async poll(): Promise<Reply | null> {
    const late = this.deps.now() >= this.deadline;
    const r = await this.send("GET", `/v1/viewer-pairings/${this.id}`, undefined, true);
    this.check();
    if (r.status === 0) {
      if (late) throw new End("expired");
      return null;
    }
    if (r.status !== 200) this.refused(r);
    const b = r.body;
    const state = b.state;
    if (state === "aborted") throw new End("aborted");
    if (typeof state !== "string" || !["open", "claimed", "nonce", "revealed", "active"].includes(state)) throw new End("failed");
    if (state !== "open" && !this.claimedSeen) {
      this.claimedSeen = true;
      if (!this.pinned) this.emit({ kind: "claimed" });
    }
    if (b.nA !== undefined || b.scope !== undefined || b.ttl !== undefined) this.pin(b);
    else if (state === "nonce" || state === "revealed" || state === "active") throw new End("failed");
    if (state === "active") {
      // Only after our own reveal can the attempt be active.
      if (!this.th) throw new End("failed");
      const bytes = fromBase64UrlExact(b.grant, GRANT_LEN);
      const deviceId = b.deviceId;
      const exp = b.viewerExpiresAt;
      if (typeof b.grant === "string" && !bytes) {
        // A grant of the wrong length is refused like any failed check.
        if (typeof deviceId === "string" && DEVICE_ID_RE.test(deviceId)) await this.revokeQuietly(deviceId);
        throw new End("failed");
      }
      if (bytes && typeof deviceId === "string" && DEVICE_ID_RE.test(deviceId)) {
        const expiresAt = typeof exp === "number" && Number.isFinite(exp) ? exp : null;
        if (!this.grant) this.grant = { bytes, deviceId, expiresAt };
        else if (this.grant.deviceId !== deviceId || hex(this.grant.bytes) !== hex(bytes)) throw new End("failed");
      }
    }
    if (late && !(this.confirmed && this.grant)) throw new End("expired");
    return r;
  }

  /** Pins nA, scope and ttl at their first receipt; any later difference aborts. */
  private pin(b: Record<string, unknown>): void {
    const nA = fromBase64UrlExact(b.nA, KEY_LEN);
    const scope = b.scope;
    const ttl = b.ttl;
    if (!nA || scope !== SCOPE || typeof ttl !== "number" || !(TTLS as readonly number[]).includes(ttl)) throw new End("failed");
    if (!this.pinned) {
      this.pinned = { nA, scope, ttl };
      return;
    }
    if (hex(this.pinned.nA) !== hex(nA) || this.pinned.scope !== scope || this.pinned.ttl !== ttl) throw new End("failed");
  }

  /** An error answer: the attempt is over, whatever it was. */
  private refused(r: Reply): never {
    if (r.status === 410) throw new End("expired");
    if (r.body.state === "aborted") throw new End("aborted");
    throw new End("failed");
  }

  // ---- plumbing ----

  /** Before a POST: nothing is sent after the deadline. */
  private ensureTime(): void {
    if (this.deps.now() >= this.deadline) throw new End("expired");
  }

  private check(): void {
    if (this.closed) throw new End("cancelled");
  }

  /** Waits for the next poll, or less when the user acted. */
  private async pause(): Promise<void> {
    this.check();
    await Promise.race([this.deps.sleep(POLL_MS), new Promise<void>((r) => (this.wake = r))]);
    this.wake = null;
    this.check();
  }

  /**
   * A request, with the attempt's token if `auth`. A network error gives
   * status 0. Every request of the attempt is idempotent on the server, so
   * the callers send it again after one.
   */
  private async send(method: string, path: string, body: unknown, auth: boolean): Promise<Reply> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (auth) headers.Authorization = `Bearer ${this.token}`;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    let res: Response;
    try {
      res = await this.deps.fetch(this.deps.base + path, {
        method,
        headers,
        credentials: "omit",
        cache: "no-store",
        referrerPolicy: "no-referrer",
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
      });
    } catch {
      return { status: 0, body: {} };
    }
    let parsed: Record<string, unknown> = {};
    try {
      const text = await res.text();
      if (text.length > MAX_RESPONSE) return { status: res.status >= 400 ? res.status : 502, body: {} };
      const j = JSON.parse(text) as unknown;
      if (j && typeof j === "object" && !Array.isArray(j)) parsed = j as Record<string, unknown>;
    } catch {
      // Not JSON: the status decides.
    }
    return { status: res.status, body: parsed };
  }

  private async abortQuietly(): Promise<void> {
    if (!this.id || !this.token) return;
    try {
      await this.deps.fetch(`${this.deps.base}/v1/viewer-pairings/${this.id}/abort`, {
        method: "POST",
        headers: { Authorization: `Bearer ${this.token}` },
        credentials: "omit",
        cache: "no-store",
      });
    } catch {
      // The attempt expires anyway.
    }
  }

  private async revokeQuietly(deviceId: string): Promise<void> {
    try {
      await this.deps.fetch(`${this.deps.base}/v1/devices/${encodeURIComponent(deviceId)}/revoke`, {
        method: "POST",
        headers: { Authorization: `Bearer ${this.token}` },
        credentials: "omit",
        cache: "no-store",
      });
    } catch {
      // The viewer expires anyway, and the app can end it.
    }
  }

  /** Drops the temporary key pair and the values that only this attempt used. */
  private drop(): void {
    this.keys = null;
    this.nB.fill(0);
    this.grant = null;
  }

  private finish(reason: EndReason, force = false): void {
    const wasClosed = this.closed;
    this.closed = true;
    this.drop();
    this.token = "";
    if (reason === "cancelled" && wasClosed && !force) return;
    this.emit({ kind: "ended", reason }, true);
  }

  private emit(e: PairingEvent, final = false): void {
    if (this.closed && !final) return;
    this.onEvent(e);
  }
}

// The browser's side of pairing v1 against a fake server that also plays
// the app (claim, nonce, approve with an HPKE-sealed grant).

import { describe, expect, it } from "vitest";
import { buf, concat, fromBase64UrlExact, hex, toBase64Url } from "../src/data/bytes";
import { importBatchKey } from "../src/data/crypto";
import { ATTEMPT_MS, PairingAttempt, type EndReason, type PairingDeps, type PairingEvent } from "../src/data/pairing";
import { appNonceOf, commitOf, grantInfo, newKeyPair, sasWords, sha256, suite, transcriptOf } from "../src/data/paircrypto";

const rand = (n: number) => crypto.getRandomValues(new Uint8Array(n));

interface Req {
  method: string;
  path: string;
  body: Record<string, unknown> | null;
  auth: string | null;
  at: number;
}

type Plain = (sA: Uint8Array, batchKey: Uint8Array) => Uint8Array;

/** The server, with the app's steps as methods the tests call from hooks. */
class Fake {
  clock = 0;
  reqs: Req[] = [];
  state = "open";
  idBytes = rand(16);
  id = toBase64Url(this.idBytes);
  code = "ABCD2345";
  tokenHash = "";
  commit: Uint8Array = new Uint8Array(0);
  nA: Uint8Array | null = null;
  sA = rand(32);
  scope = "batch-v1";
  ttl = 3600;
  pkB: Uint8Array | null = null;
  nB: Uint8Array | null = null;
  confirmed = false;
  grant: Uint8Array | null = null;
  deviceId = "viewer-1";
  batchKey = rand(32);
  /** Called before each GET is answered (the app acts between polls). */
  onGet: (f: Fake, n: number) => void | Promise<void> = () => {};
  /** Overrides the next create answers. */
  createAnswers: number[] = [];
  /** Returns active with the grant as soon as one exists, without the browser's confirm. */
  activeBeforeConfirm = false;
  gets = 0;

  deps(extra: Partial<PairingDeps> = {}): PairingDeps {
    return {
      fetch: this.fetch,
      base: "",
      now: () => this.clock,
      wall: () => 1_760_000_000_000 + this.clock,
      sleep: (ms) => {
        this.clock += ms;
        return new Promise((r) => setTimeout(r, 0));
      },
      random: rand,
      keyPair: newKeyPair,
      importKey: importBatchKey,
      ...extra,
    };
  }

  // ---- the app ----

  claim() {
    if (this.state === "open") this.state = "claimed";
  }
  async nonce() {
    this.nA = await appNonceOf(this.sA);
    this.state = "nonce";
  }
  async th(): Promise<Uint8Array> {
    return transcriptOf({ id: this.idBytes, code: this.code, c: this.commit, nA: this.nA!, scope: this.scope, ttl: this.ttl, pkB: this.pkB!, nB: this.nB! });
  }
  async approve(plain: Plain = (sA, k) => concat([Uint8Array.of(1), sA, k])) {
    const s = suite();
    const pk = await s.kem.importKey("raw", buf(this.pkB!).buffer, true);
    const ctx = await s.createSenderContext({ recipientPublicKey: pk, info: buf(grantInfo(await this.th())).buffer });
    const ct = new Uint8Array(await ctx.seal(buf(plain(this.sA, this.batchKey)).buffer, new ArrayBuffer(0)));
    this.grant = concat([new Uint8Array(ctx.enc), ct]);
    this.activate();
  }
  activate() {
    if (this.state === "revealed" && this.grant && (this.confirmed || this.activeBeforeConfirm)) this.state = "active";
  }

  // ---- HTTP ----

  fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const path = String(input);
    const method = init?.method ?? "GET";
    const headers = new Headers(init?.headers);
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : null;
    const auth = headers.get("Authorization")?.replace(/^Bearer /, "") ?? null;
    this.reqs.push({ method, path, body, auth, at: this.clock });
    const json = (status: number, b: unknown) => new Response(JSON.stringify(b), { status, headers: { "Content-Type": "application/json" } });
    if (path === "/v1/viewer-pairings" && method === "POST") {
      const status = this.createAnswers.shift() ?? 201;
      if (status === 409) return json(409, { error: "token already in use" });
      this.tokenHash = String(body!.tokenHash);
      this.commit = fromBase64UrlExact(body!.commit, 32)!;
      return json(201, { id: this.id, code: this.code, expiresAt: 1_760_000_600_000 });
    }
    if (path.startsWith("/v1/devices/") && path.endsWith("/revoke")) return json(200, { revoked: true });
    if (!auth || hex(await sha256(new TextEncoder().encode(auth))) !== this.tokenHash) return json(401, { error: "unauthorized" });
    const base = `/v1/viewer-pairings/${this.id}`;
    if (path === base && method === "GET") {
      await this.onGet(this, ++this.gets);
      this.activate();
      const out: Record<string, unknown> = { state: this.state, expiresAt: 1_760_000_600_000 };
      if (this.nA && this.state !== "aborted") Object.assign(out, { nA: toBase64Url(this.nA), scope: this.scope, ttl: this.ttl });
      if (this.state === "active") Object.assign(out, { grant: toBase64Url(this.grant!), deviceId: this.deviceId, viewerExpiresAt: 1_760_003_600_000 });
      return json(200, out);
    }
    if (path === `${base}/reveal`) {
      if (this.state !== "nonce") return json(409, { error: "invalid state", state: this.state });
      this.pkB = fromBase64UrlExact(body!.pkB, 32);
      this.nB = fromBase64UrlExact(body!.nB, 32);
      expect(hex(await commitOf(this.pkB!, this.nB!))).toBe(hex(this.commit));
      this.state = "revealed";
      return json(200, { state: this.state });
    }
    if (path === `${base}/confirm`) {
      if (this.state !== "revealed" && this.state !== "active") return json(409, { error: "invalid state", state: this.state });
      this.confirmed = true;
      this.activate();
      return json(200, { state: this.state });
    }
    if (path === `${base}/abort`) {
      if (this.state !== "active") this.state = "aborted";
      return json(200, { state: this.state });
    }
    return json(404, { error: "not found" });
  };
}

/** The honest app: claims on the 1st poll, sends its nonce on the 2nd, approves once revealed. */
const honestApp = async (f: Fake, n: number) => {
  if (n === 1) f.claim();
  if (n === 2) await f.nonce();
  if (f.state === "revealed" && !f.grant) await f.approve();
};

async function runAttempt(f: Fake, react: (e: PairingEvent, a: PairingAttempt) => void, extra: Partial<PairingDeps> = {}) {
  const events: PairingEvent[] = [];
  let a: PairingAttempt | null = null;
  a = new PairingAttempt(f.deps(extra), (e) => {
    events.push(e);
    react(e, a!);
  });
  await a.run();
  return { events, attempt: a };
}

const confirmOnWords = (e: PairingEvent, a: PairingAttempt) => {
  if (e.kind === "words") a.confirm();
};
const endOf = (events: PairingEvent[]): EndReason | "done" | null => {
  const last = events[events.length - 1];
  return last?.kind === "done" ? "done" : last?.kind === "ended" ? last.reason : null;
};

describe("pairing v1, browser side", () => {
  it("pairs with an honest app: same words, a non-extractable batch key, memory-only session", async () => {
    const f = new Fake();
    f.onGet = honestApp;
    const { events } = await runAttempt(f, confirmOnWords);
    expect(events.map((e) => e.kind)).toEqual(["code", "claimed", "words", "waiting", "done"]);
    const words = events.find((e) => e.kind === "words") as Extract<PairingEvent, { kind: "words" }>;
    expect(words.words).toEqual(await sasWords(await f.th()));
    expect(words).toMatchObject({ scope: "batch-v1", ttl: 3600 });
    const done = events.at(-1) as Extract<PairingEvent, { kind: "done" }>;
    const s = done.session;
    expect(s).toMatchObject({ deviceId: "viewer-1", storage: "tab", source: "pairing", expiresAt: 1_760_003_600_000 });
    expect(s.token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(hex(await sha256(new TextEncoder().encode(s.token)))).toBe(f.tokenHash);
    expect(s.batchKey.extractable).toBe(false);
    expect(s.batchKey.algorithm.name).toBe("AES-GCM");
    // The key is the app's batch key: it opens what that key sealed.
    const raw = await crypto.subtle.importKey("raw", buf(f.batchKey), "AES-GCM", false, ["encrypt"]);
    const iv = rand(12);
    const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv: buf(iv) }, raw, buf(new TextEncoder().encode("ok")));
    expect(new TextDecoder().decode(await crypto.subtle.decrypt({ name: "AES-GCM", iv: buf(iv) }, s.batchKey, ct))).toBe("ok");
    // pkB and nB only after nA; nothing but GETs carries no token.
    const reveal = f.reqs.findIndex((r) => r.path.endsWith("/reveal"));
    const firstNonce = f.gets >= 2 ? 2 : Infinity;
    expect(f.reqs.filter((r, i) => i < reveal && r.method === "GET").length).toBeGreaterThanOrEqual(firstNonce);
    expect(f.reqs.slice(1).every((r) => r.auth === s.token)).toBe(true);
    expect(f.reqs[0]!.body).toEqual({ commit: toBase64Url(f.commit), tokenHash: f.tokenHash });
  });

  it("does not reveal pkB and nB before nA arrives", async () => {
    const f = new Fake();
    f.onGet = async (x, n) => {
      if (n === 1) x.claim();
      if (n === 6) await x.nonce();
      if (x.state === "revealed" && !x.grant) await x.approve();
    };
    const { events } = await runAttempt(f, confirmOnWords);
    expect(endOf(events)).toBe("done");
    const reveal = f.reqs.findIndex((r) => r.path.endsWith("/reveal"));
    expect(f.reqs.slice(0, reveal).filter((r) => r.method === "GET")).toHaveLength(6);
  });

  it("aborts when nA, scope or ttl changes after it was pinned", async () => {
    for (const change of [
      (x: Fake) => (x.nA = rand(32)),
      (x: Fake) => (x.ttl = 86400),
      (x: Fake) => (x.scope = "batch-v2"),
    ]) {
      const f = new Fake();
      f.onGet = async (x, n) => {
        if (n === 1) x.claim();
        if (n === 2) await x.nonce();
        if (n === 4) change(x);
      };
      const { events } = await runAttempt(f, () => {});
      expect(endOf(events)).toBe("failed");
      expect(f.reqs.at(-1)!.path).toMatch(/\/abort$/);
      expect(events.some((e) => e.kind === "done")).toBe(false);
    }
  });

  it("refuses a scope or ttl other than v1's", async () => {
    for (const [scope, ttl] of [
      ["batch-v2", 3600],
      ["batch-v1", 7200],
    ] as const) {
      const f = new Fake();
      f.scope = scope;
      f.ttl = ttl;
      f.onGet = honestApp;
      const { events } = await runAttempt(f, confirmOnWords);
      expect(endOf(events)).toBe("failed");
      expect(f.reqs.some((r) => r.path.endsWith("/reveal"))).toBe(false);
    }
  });

  it("never opens a grant before 「一致する」", async () => {
    const f = new Fake();
    f.activeBeforeConfirm = true;
    let imported = 0;
    let confirmAt = -1;
    f.onGet = async (x, n) => {
      await honestApp(x, n);
      if (n === 10) confirmAt = n;
    };
    let attempt: PairingAttempt | null = null;
    const { events } = await runAttempt(
      f,
      (e, a) => {
        attempt = a;
        void e;
      },
      {
        importKey: async (raw) => {
          imported++;
          expect(confirmAt).toBeGreaterThan(0);
          return importBatchKey(raw);
        },
        sleep: (ms) => {
          f.clock += ms;
          // The user presses 「一致する」 only after several polls saw the grant.
          if (f.gets === 10) attempt?.confirm();
          return new Promise((r) => setTimeout(r, 0));
        },
      },
    );
    expect(endOf(events)).toBe("done");
    expect(imported).toBe(1);
  });

  it("refuses a grant whose sA does not match nA, and ends the browser's registration", async () => {
    const f = new Fake();
    f.onGet = async (x, n) => {
      if (n === 1) x.claim();
      if (n === 2) await x.nonce();
      if (x.state === "revealed" && !x.grant) await x.approve((_sA, k) => concat([Uint8Array.of(1), rand(32), k]));
    };
    let imported = 0;
    const { events } = await runAttempt(f, confirmOnWords, { importKey: async (r) => (imported++, importBatchKey(r)) });
    expect(endOf(events)).toBe("failed");
    expect(imported).toBe(0);
    expect(f.reqs.some((r) => r.path === "/v1/devices/viewer-1/revoke" && r.method === "POST")).toBe(true);
  });

  it("refuses a plaintext of another length or version", async () => {
    for (const plain of [
      (sA: Uint8Array, k: Uint8Array) => concat([Uint8Array.of(2), sA, k]),
      (sA: Uint8Array, k: Uint8Array) => concat([Uint8Array.of(1), sA, k, Uint8Array.of(0)]),
      (sA: Uint8Array, k: Uint8Array) => concat([Uint8Array.of(1), sA, k.subarray(1)]),
    ]) {
      const f = new Fake();
      f.onGet = async (x, n) => {
        if (n === 1) x.claim();
        if (n === 2) await x.nonce();
        if (x.state === "revealed" && !x.grant) await x.approve(plain);
      };
      const { events } = await runAttempt(f, confirmOnWords);
      expect(endOf(events)).toBe("failed");
    }
  });

  it("「一致しない」 aborts on the server and drops the attempt", async () => {
    const f = new Fake();
    f.onGet = honestApp;
    const { events } = await runAttempt(f, (e, a) => {
      if (e.kind === "words") void a.mismatch();
    });
    expect(endOf(events)).toBe("mismatch");
    expect(f.state).toBe("aborted");
    expect(f.reqs.some((r) => r.path.endsWith("/confirm"))).toBe(false);
    expect(events.filter((e) => e.kind === "ended")).toHaveLength(1);
  });

  it("an attempt the app aborted ends as aborted", async () => {
    const f = new Fake();
    f.onGet = async (x, n) => {
      await honestApp(x, n);
      if (n === 3) x.state = "aborted";
    };
    const { events } = await runAttempt(f, () => {});
    expect(endOf(events)).toBe("aborted");
  });

  it("expires 10 minutes after the create response: no request is sent after it but one GET", async () => {
    const f = new Fake();
    f.onGet = (x, n) => {
      if (n === 1) x.claim();
    };
    const { events } = await runAttempt(f, () => {});
    expect(endOf(events)).toBe("expired");
    const late = f.reqs.filter((r) => r.at >= ATTEMPT_MS);
    expect(late).toHaveLength(1);
    expect(late[0]!.method).toBe("GET");
  });

  it("past the deadline, one GET still finds an attempt that became active", async () => {
    const f = new Fake();
    // The app approves only after the browser's deadline; the server activated it just before.
    f.onGet = async (x, n) => {
      if (n === 1) x.claim();
      if (n === 2) await x.nonce();
      if (x.state === "revealed" && x.clock >= ATTEMPT_MS && !x.grant) await x.approve();
    };
    const { events } = await runAttempt(f, confirmOnWords);
    expect(endOf(events)).toBe("done");
    expect(f.reqs.filter((r) => r.at >= ATTEMPT_MS)).toHaveLength(1);
  });

  it("retries the create with a new token after 409 token already in use", async () => {
    const f = new Fake();
    f.createAnswers = [409];
    f.onGet = honestApp;
    const { events } = await runAttempt(f, confirmOnWords);
    expect(endOf(events)).toBe("done");
    const creates = f.reqs.filter((r) => r.path === "/v1/viewer-pairings");
    expect(creates).toHaveLength(2);
    expect(creates[0]!.body!.tokenHash).not.toBe(creates[1]!.body!.tokenHash);
    expect(creates[0]!.body!.commit).toBe(creates[1]!.body!.commit);
  });

  it("gives up after a second 409", async () => {
    const f = new Fake();
    f.createAnswers = [409, 409];
    const { events } = await runAttempt(f, () => {});
    expect(endOf(events)).toBe("failed");
  });

  it("discards results of an attempt the page left", async () => {
    const f = new Fake();
    f.onGet = honestApp;
    const events: PairingEvent[] = [];
    let a: PairingAttempt | null = null;
    a = new PairingAttempt(f.deps(), (e) => {
      events.push(e);
      if (e.kind === "claimed") void a!.cancel();
    });
    await a.run();
    await new Promise((r) => setTimeout(r, 10));
    expect(events.map((e) => e.kind)).toEqual(["code", "claimed"]);
    expect(f.reqs.filter((r) => r.path.endsWith("/abort"))).toHaveLength(1);
    expect(f.state).toBe("aborted");
    // Nothing was sent after the abort.
    expect(f.reqs.at(-1)!.path).toMatch(/\/abort$/);
    expect(a.ended).toBe(true);
  });

  it("refuses a malformed create answer", async () => {
    const f = new Fake();
    f.id = "short";
    const { events } = await runAttempt(f, () => {});
    expect(endOf(events)).toBe("failed");
  });
});

import { describe, expect, it } from "vitest";
import { Api } from "../src/data/api";
import { fromHex } from "../src/data/bytes";
import { importBatchKey } from "../src/data/crypto";
import { MemoryStore, Sessions, devSession, parseKeyText, signOut, type ViewerSession } from "../src/data/session";
import { ViewerStore } from "../src/data/store";
import { TOKEN, fakeServer, fixtureJson, syncMeta } from "./helpers";

const DEV_A = "0f5c2a8e-7d1b-4c3e-9a61-2b7d5e8f4a10";
const DEV_B = "6a3e9b0c-1f2d-4e5a-8b7c-9d0e1f2a3b4c";

async function store(opts: Parameters<typeof fakeServer>[0] = {}) {
  const s = fakeServer(opts);
  const key = await importBatchKey(fromHex(syncMeta().batchKeyHex));
  return { store: new ViewerStore(new Api({ token: TOKEN, fetch: s.fetch }), key), calls: s.calls };
}

describe("ViewerStore over the app's batches", () => {
  it("reads devices, small streams and the newest status of each device", async () => {
    const { store: st } = await store();
    await st.init();
    expect(st.deviceIds()).toEqual([DEV_A, DEV_B]);
    expect(st.recordingDevices()).toEqual([DEV_A]);
    expect(st.status.get(DEV_A)?.status?.battery.pct).toBe(78);
    expect(st.status.get(DEV_A)?.seq).toBe(1);
    expect(st.status.get(DEV_B)?.status?.recording.on).toBe(false);
    // The later snapshot of place 2 wins.
    expect(st.places.find((p) => p.key === `${DEV_A}/2`)?.name).toBe("大手町オフィス");
    expect(st.checkIns.map((c) => c.venue)).toEqual(["丸の内 オアゾ"]);
    expect(st.overrides).toHaveLength(1);
  });

  it("builds the day the app builds from the archive, with places and overrides", async () => {
    const { store: st } = await store();
    await st.init();
    await st.loadMore(DEV_A, 7);
    expect(st.days(DEV_A)).toEqual(["2026-10-05"]);
    expect(st.hasMore(DEV_A)).toBe(false);
    const day = st.day(DEV_A, "2026-10-05")!;
    const exp = fixtureJson<{ segments: { type: string; start: number; end: number }[]; summary: { steps: number } }>("commute-timeline.json");
    expect(day.segments.map((s) => [s.type, s.start, s.end])).toEqual(exp.segments.map((s) => [s.type, s.start, s.end]));
    expect(day.summary.steps).toBe(exp.summary.steps);
    const visits = day.segments.filter((s) => s.type === "visit");
    expect(visits[0]!.placeKey).toBe(`${DEV_A}/1`);
    expect(visits.some((v) => v.placeKey === `${DEV_A}/2`)).toBe(true);
    expect(day.checkIns).toHaveLength(1);
    expect(day.partial).toBe(false);
    expect(st.failures(DEV_A)).toBe(0);
  });

  it("keeps going when a batch cannot be read, and counts it", async () => {
    const { store: st } = await store({ tamper: (p, b) => (p.endsWith("/archive/8") ? b.slice(0, -1) : b) });
    await st.init();
    await st.loadMore(DEV_A, 7);
    expect(st.failures(DEV_A)).toBe(1);
    // The motion file (archive/8, after the fixes parts) is missing: positions still show, steps do not.
    expect(st.day(DEV_A, "2026-10-05")!.summary.steps).toBe(0);
  });

  it("reports a revoked token", async () => {
    const { store: st } = await store({ status: (p) => (p === "/v1/devices" ? 401 : null) });
    let fired = false;
    st.addEventListener("unauthorized", () => (fired = true));
    await expect(st.init()).rejects.toThrow();
    expect(fired).toBe(true);
  });
});

describe("session (dev stub)", () => {
  it("parses a pasted batch key as hex or base64", () => {
    const hex = syncMeta().batchKeyHex;
    const b64 = btoa(String.fromCharCode(...fromHex(hex)));
    expect(parseKeyText(hex)).toEqual(fromHex(hex));
    expect(parseKeyText(` ${b64}\n`)).toEqual(fromHex(hex));
    expect(parseKeyText(b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""))).toEqual(fromHex(hex));
    expect(() => parseKeyText("abcd")).toThrow();
  });

  it("makes a dev session with a non-extractable key, and forgets it on clear", async () => {
    const s = await devSession(TOKEN, syncMeta().batchKeyHex);
    expect(s.batchKey.extractable).toBe(false);
    await expect(devSession("short", syncMeta().batchKeyHex)).rejects.toThrow();
    const mem = new MemoryStore();
    const persistent = new MemoryStore();
    const sessions = new Sessions(mem, persistent);
    await sessions.save(s);
    expect(await persistent.load()).toBeNull();
    await sessions.save({ ...s, storage: "browser" });
    expect(await persistent.load()).not.toBeNull();
    await sessions.clear();
    expect(await sessions.load()).toBeNull();
  });
});

describe("登録の解除 (signOut)", () => {
  it("forgets the key and the token first; a server that never answers the revocation holds nothing back", async () => {
    const batchKey = await importBatchKey(fromHex(syncMeta().batchKeyHex));
    const s: ViewerSession = { token: TOKEN, batchKey, deviceId: "viewer-1", name: "このブラウザ", registeredAt: 0, expiresAt: null, storage: "tab", source: "pairing" };
    const sessions = new Sessions(new MemoryStore(), new MemoryStore());
    await sessions.save(s);
    const calls: { url: string; auth: string | null; signal: AbortSignal | null }[] = [];
    let forgotten = false;
    // Never answers; only the request's signal ends it.
    const hanging = ((url: string, init?: RequestInit) => {
      expect(forgotten).toBe(true);
      calls.push({ url, auth: new Headers(init?.headers).get("Authorization"), signal: init?.signal ?? null });
      return new Promise<Response>((_, reject) => init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError"))));
    }) as typeof fetch;
    const { revoked } = await signOut(
      s,
      async () => {
        await sessions.clear();
        forgotten = true;
      },
      { fetch: hanging, timeoutMs: 30 },
    );
    expect(await sessions.load()).toBeNull();
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({ url: "/v1/devices/viewer-1/revoke", auth: `Bearer ${TOKEN}` });
    expect(calls[0]!.signal!.aborted).toBe(false);
    await revoked;
    expect(calls[0]!.signal!.aborted).toBe(true);
  });

  it("a dev session (no device id) only forgets", async () => {
    let called = false;
    const never = (() => {
      called = true;
      return Promise.reject(new Error("not expected"));
    }) as unknown as typeof fetch;
    const { revoked } = await signOut(null, async () => {}, { fetch: never });
    await revoked;
    expect(called).toBe(false);
  });
});

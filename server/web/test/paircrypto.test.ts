// Pairing v1 values: the design's SAS vector, the pinned word list, and
// grants sealed by the app's HPKE (Tink 1.23.0), from the fixture that
// core's ViewerPairingFixtures writes (./gradlew :core:webFixtures).

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { buf, concat, fromHex, hex } from "../src/data/bytes";
import {
  appNonceOf,
  commitOf,
  GrantError,
  grantInfo,
  openGrant,
  sasIndices,
  sasWords,
  sha256,
  suite,
  transcriptOf,
  WORDLIST_SHA256,
  wordlist,
  type TranscriptFields,
} from "../src/data/paircrypto";
import { FIXTURES } from "./helpers";

const fill = (b: number) => new Uint8Array(32).fill(b);

describe("pairing v1 values", () => {
  it("pins the BIP-39 Japanese word list by its SHA-256", async () => {
    const raw = new Uint8Array(readFileSync(join(import.meta.dirname, "../src/data/bip39-japanese.txt")));
    expect(hex(await sha256(raw))).toBe(WORDLIST_SHA256);
    const list = await wordlist();
    expect(list).toHaveLength(2048);
    expect(list.every((w) => w === w.normalize("NFC"))).toBe(true);
  });

  it("matches the design's test vector", async () => {
    const pkB = fill(0x33);
    const nB = fill(0x11);
    const c = await commitOf(pkB, nB);
    expect(hex(c)).toBe("bd511eec808fd863c98d184c6c3365b4795dd5faa744c4eb4739745b643c90a6");
    const nA = await appNonceOf(fill(0x44));
    expect(hex(nA)).toBe("12f1f75587df148a55d473a566e6af39dfb694346eb15f318296e00ec72c0428");
    const id = Uint8Array.from({ length: 16 }, (_, i) => i);
    const th = await transcriptOf({ id, code: "ABCD2345", c, nA, scope: "batch-v1", ttl: 3600, pkB, nB });
    expect(hex(th)).toBe("7d6395cd068e7461034748a495ad8260d04489d466cb7a0b33f14b157cd9d3a8");
    expect(hex((await sha256(new TextEncoder().encode("ashidori pair sas v1"), th)).slice(0, 6))).toBe("44999b593025");
    expect(await sasIndices(th)).toEqual([548, 1638, 1714, 770]);
    expect(await sasWords(th)).toEqual(["けっせき", "ぴっちり", "ふみん", "ざんしょ"]);
  });

  it("refuses transcript fields of the wrong length", async () => {
    const ok: TranscriptFields = { id: new Uint8Array(16), code: "ABCD2345", c: fill(1), nA: fill(2), scope: "batch-v1", ttl: 3600, pkB: fill(3), nB: fill(4) };
    await expect(transcriptOf({ ...ok, id: new Uint8Array(15) })).rejects.toThrow();
    await expect(transcriptOf({ ...ok, pkB: new Uint8Array(33) })).rejects.toThrow();
    await expect(transcriptOf({ ...ok, code: "abcd2345" })).rejects.toThrow();
    await expect(transcriptOf(ok)).resolves.toHaveLength(32);
  });
});

// ---- interop with the app (Tink) ----

interface Fixture {
  wordlistSha256: string;
  sas: { id: string; code: string; pkB: string; nB: string; sA: string; scope: string; ttl: number; c: string; nA: string; th: string; d: string; indices: number[]; words: string[] };
  tamper: { field: string; value: string | number; th: string; words: string[] }[];
  grant: { skB: string; pkB: string; nB: string; id: string; code: string; sA: string; nA: string; c: string; scope: string; ttl: number; th: string; info: string; batchKey: string; grant: string };
  wrongSa: { sA: string; grant: string };
  badVersion: { grant: string };
  longPlain: { grant: string };
  shortPlain: { grant: string };
  lowOrder: string[];
}

const FIXTURE = join(FIXTURES, "viewer-pairing.json");
const present = existsSync(FIXTURE);
const fx = (): Fixture => JSON.parse(readFileSync(FIXTURE, "utf8")) as Fixture;

function fieldsOf(g: { id: string; code: string; c: string; nA: string; scope: string; ttl: number; pkB: string; nB: string }): TranscriptFields {
  return { id: fromHex(g.id), code: g.code, c: fromHex(g.c), nA: fromHex(g.nA), scope: g.scope, ttl: g.ttl, pkB: fromHex(g.pkB), nB: fromHex(g.nB) };
}

function withField(f: TranscriptFields, field: string, value: string | number): TranscriptFields {
  if (field === "code" || field === "scope") return { ...f, [field]: value as string };
  if (field === "ttl") return { ...f, ttl: value as number };
  return { ...f, [field]: fromHex(value as string) };
}

/** The recipient as the browser holds it: the private key and its public key. */
async function recipient(g: Fixture["grant"]): Promise<CryptoKeyPair> {
  const kem = suite().kem;
  const privateKey = await kem.importKey("raw", buf(fromHex(g.skB)).buffer, false);
  const publicKey = await kem.importKey("raw", buf(fromHex(g.pkB)).buffer, true);
  return { privateKey, publicKey };
}

async function refusal(p: Promise<unknown>): Promise<string> {
  try {
    await p;
    return "accepted";
  } catch (e) {
    return e instanceof GrantError ? e.kind : `other: ${String(e)}`;
  }
}

// Skipped (and named so) only while the fixture is missing: ./gradlew :core:webFixtures writes it.
describe.skipIf(!present)("pairing v1 interop with the app (fixture of Tink 1.23.0, test/fixtures/viewer-pairing.json)", () => {
  it("has the pinned word list and the design's SAS vector", async () => {
    const f = fx();
    expect(f.wordlistSha256).toBe(WORDLIST_SHA256);
    const fields = fieldsOf(f.sas);
    expect(hex(await commitOf(fields.pkB, fields.nB))).toBe(f.sas.c);
    expect(hex(await appNonceOf(fromHex(f.sas.sA)))).toBe(f.sas.nA);
    const th = await transcriptOf(fields);
    expect(hex(th)).toBe(f.sas.th);
    expect(hex(await sha256(new TextEncoder().encode("ashidori pair sas v1"), th))).toBe(f.sas.d);
    expect(await sasIndices(th)).toEqual(f.sas.indices);
    expect(await sasWords(th)).toEqual(f.sas.words);
  });

  it("changes th and the words when any one transcript field changes", async () => {
    const f = fx();
    const base = fieldsOf(f.sas);
    expect(new Set(f.tamper.map((t) => t.field))).toEqual(new Set(["id", "code", "c", "nA", "scope", "ttl", "pkB", "nB"]));
    for (const t of f.tamper) {
      const th = await transcriptOf(withField(base, t.field, t.value));
      expect(hex(th), t.field).toBe(t.th);
      expect(hex(th), t.field).not.toBe(f.sas.th);
      expect(await sasWords(th), t.field).toEqual(t.words);
      expect(t.words, t.field).not.toEqual(f.sas.words);
    }
  });

  it("opens the app's grant with @hpke/core: info, empty aad, 113 bytes", async () => {
    const f = fx();
    const g = f.grant;
    const fields = fieldsOf(g);
    expect(hex(await commitOf(fields.pkB, fields.nB))).toBe(g.c);
    expect(hex(await appNonceOf(fromHex(g.sA)))).toBe(g.nA);
    const th = await transcriptOf(fields);
    expect(hex(th)).toBe(g.th);
    expect(hex(grantInfo(th))).toBe(g.info);
    expect(fromHex(g.grant)).toHaveLength(113);
    const key = await openGrant(await recipient(g), th, fields.nA, fromHex(g.grant));
    expect(hex(key)).toBe(g.batchKey);
  });

  it("fails to open the grant under a transcript with any field changed", async () => {
    const f = fx();
    const g = f.grant;
    const base = fieldsOf(g);
    const r = await recipient(g);
    const changed: [string, string | number][] = [
      ["id", hex(concat([fromHex(g.id).slice(0, 15), Uint8Array.of(fromHex(g.id)[15]! ^ 1)]))],
      ["code", g.code === "ABCD2345" ? "ABCD2346" : "ABCD2345"],
      ["c", hex(concat([fromHex(g.c).slice(0, 31), Uint8Array.of(fromHex(g.c)[31]! ^ 1)]))],
      ["nA", hex(concat([fromHex(g.nA).slice(0, 31), Uint8Array.of(fromHex(g.nA)[31]! ^ 1)]))],
      ["scope", "batch-v2"],
      ["ttl", g.ttl === 3600 ? 86400 : 3600],
      ["pkB", hex(concat([fromHex(g.pkB).slice(0, 31), Uint8Array.of(fromHex(g.pkB)[31]! ^ 1)]))],
      ["nB", hex(concat([fromHex(g.nB).slice(0, 31), Uint8Array.of(fromHex(g.nB)[31]! ^ 1)]))],
    ];
    for (const [field, value] of changed) {
      const th = await transcriptOf(withField(base, field, value));
      expect(await refusal(openGrant(r, th, base.nA, fromHex(g.grant))), field).toBe("open");
    }
  });

  it("refuses a wrong sA, another version, other plaintext lengths and grants of another length", async () => {
    const f = fx();
    const g = f.grant;
    const fields = fieldsOf(g);
    const r = await recipient(g);
    const th = await transcriptOf(fields);
    const grant = fromHex(g.grant);
    expect(hex(await appNonceOf(fromHex(f.wrongSa.sA)))).not.toBe(g.nA);
    expect(await refusal(openGrant(r, th, fields.nA, fromHex(f.wrongSa.grant)))).toBe("sA");
    expect(await refusal(openGrant(r, th, fields.nA, fromHex(f.badVersion.grant)))).toBe("version");
    // 66 and 64 bytes of plaintext make grants of 114 and 112 bytes: refused before opening.
    expect(await refusal(openGrant(r, th, fields.nA, fromHex(f.longPlain.grant)))).toBe("length");
    expect(await refusal(openGrant(r, th, fields.nA, fromHex(f.shortPlain.grant)))).toBe("length");
    expect(await refusal(openGrant(r, th, fields.nA, concat([grant, Uint8Array.of(0)])))).toBe("length");
    expect(await refusal(openGrant(r, th, fields.nA, grant.slice(0, 112)))).toBe("length");
    // A flipped byte of the ciphertext or of enc.
    for (const i of [0, 31, 32, 112]) {
      const bad = grant.slice();
      bad[i]! ^= 1;
      expect(await refusal(openGrant(r, th, fields.nA, bad)), String(i)).toBe("open");
    }
    // Opened under another recipient key: refused.
    const other = (await crypto.subtle.generateKey({ name: "X25519" }, false, ["deriveBits"])) as CryptoKeyPair;
    expect(await refusal(openGrant(other, th, fields.nA, grant))).toBe("open");
    // The real grant still opens with the same values.
    expect(hex(await openGrant(r, th, fields.nA, grant))).toBe(g.batchKey);
  });

  it("lists low-order keys that give an all-zero shared secret (the app refuses them before sealing)", async () => {
    const f = fx();
    expect(f.lowOrder.length).toBeGreaterThan(0);
    const kem = suite().kem;
    for (const u of f.lowOrder) {
      expect(fromHex(u)).toHaveLength(32);
      // Sealing to such a key fails here as well: X25519 refuses an all-zero result.
      const pk = await kem.importKey("raw", buf(fromHex(u)).buffer, true).catch(() => null);
      if (!pk) continue;
      const sealed = await suite()
        .createSenderContext({ recipientPublicKey: pk, info: new ArrayBuffer(0) })
        .then(() => "sealed")
        .catch(() => "refused");
      expect(sealed, u).toBe("refused");
    }
  });
});

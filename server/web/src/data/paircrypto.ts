// The values of browser pairing v1 (docs/design/browser-pairing-v1.md,
// "値と符号化"): the commit, the app's nonce, the transcript th, the four
// confirmation words, and the check and opening of the grant.
//
// H is SHA-256 and ‖ concatenation; labels are ASCII without a terminator.

import { Aes256Gcm, CipherSuite, DhkemX25519HkdfSha256, HkdfSha256 } from "@hpke/core";
import wordlistText from "./bip39-japanese.txt?raw";
import { buf, concat, equalBytes, hex } from "./bytes";

const enc = new TextEncoder();
const label = (s: string) => enc.encode(s);

export const LABEL_COMMIT = "ashidori pair commit v1";
export const LABEL_APP = "ashidori pair app v1";
export const LABEL_TH = "ashidori pair v1";
export const LABEL_SAS = "ashidori pair sas v1";
export const LABEL_GRANT = "ashidori viewer grant v1";

/** The only scope of v1, and the viewer lifetimes the app may choose (seconds). */
export const SCOPE = "batch-v1";
export const TTLS = [3600, 86400] as const;

export const ID_LEN = 16;
export const KEY_LEN = 32;
/** enc (32) ‖ ciphertext of 65 bytes with its 16-byte tag (81). */
export const GRANT_LEN = 113;
/** version (1) ‖ sA (32) ‖ batch key (32). */
export const PLAIN_LEN = 65;
export const GRANT_VERSION = 0x01;

export const CODE_RE = /^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{8}$/;

/** The SHA-256 of bitcoin/bips bip-0039/japanese.txt that the design pins. */
export const WORDLIST_SHA256 = "2eed0aef492291e061633d7ad8117f1a2b03eb80a29d0e4e3117ac2528d05ffd";

export async function sha256(...parts: Uint8Array[]): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", buf(concat(parts))));
}

/** c = H("ashidori pair commit v1" ‖ pkB ‖ nB). */
export function commitOf(pkB: Uint8Array, nB: Uint8Array): Promise<Uint8Array> {
  return sha256(label(LABEL_COMMIT), pkB, nB);
}

/** nA = H("ashidori pair app v1" ‖ sA). */
export function appNonceOf(sA: Uint8Array): Promise<Uint8Array> {
  return sha256(label(LABEL_APP), sA);
}

export interface TranscriptFields {
  id: Uint8Array;
  code: string;
  c: Uint8Array;
  nA: Uint8Array;
  scope: string;
  ttl: number;
  pkB: Uint8Array;
  nB: Uint8Array;
}

/**
 * th = H("ashidori pair v1" ‖ id ‖ code ‖ c ‖ nA ‖ len(scope) (1) ‖ scope ‖
 * ttl (4, big-endian) ‖ pkB ‖ nB). scope is the only variable-length field
 * and carries its length, so the input splits in exactly one way.
 */
export async function transcriptOf(f: TranscriptFields): Promise<Uint8Array> {
  if (f.id.byteLength !== ID_LEN) throw new Error("id must be 16 bytes");
  if (!CODE_RE.test(f.code)) throw new Error("malformed code");
  for (const [n, v] of [["c", f.c], ["nA", f.nA], ["pkB", f.pkB], ["nB", f.nB]] as const) {
    if (v.byteLength !== KEY_LEN) throw new Error(`${n} must be 32 bytes`);
  }
  const scope = enc.encode(f.scope);
  if (scope.byteLength > 255) throw new Error("scope too long");
  if (!Number.isInteger(f.ttl) || f.ttl < 0 || f.ttl > 0xffffffff) throw new Error("ttl out of range");
  const ttl = new Uint8Array(4);
  new DataView(ttl.buffer).setUint32(0, f.ttl, false);
  return sha256(label(LABEL_TH), f.id, enc.encode(f.code), f.c, f.nA, Uint8Array.of(scope.byteLength), scope, ttl, f.pkB, f.nB);
}

let words: Promise<string[]> | null = null;

/** The 2048 words, NFC, after checking the file against the pinned SHA-256. */
export function wordlist(): Promise<string[]> {
  words ??= (async () => {
    const digest = hex(await sha256(enc.encode(wordlistText)));
    if (digest !== WORDLIST_SHA256) throw new Error("the word list differs from the pinned one");
    const list = wordlistText.split("\n").filter((l) => l.length > 0).map((w) => w.normalize("NFC"));
    if (list.length !== 2048) throw new Error("the word list must have 2048 words");
    return list;
  })();
  return words;
}

/** The four 11-bit numbers: the first 44 bits of H("ashidori pair sas v1" ‖ th), big-endian. */
export async function sasIndices(th: Uint8Array): Promise<number[]> {
  const d = await sha256(label(LABEL_SAS), th);
  let x = 0n;
  for (let i = 0; i < 6; i++) x = (x << 8n) | BigInt(d[i]!);
  x >>= 4n; // 48 bits → the first 44
  return [3, 2, 1, 0].map((k) => Number((x >> BigInt(11 * k)) & 2047n));
}

export async function sasWords(th: Uint8Array): Promise<string[]> {
  const list = await wordlist();
  return (await sasIndices(th)).map((i) => list[i]!);
}

/** info = "ashidori viewer grant v1" ‖ th. */
export function grantInfo(th: Uint8Array): Uint8Array {
  return concat([label(LABEL_GRANT), th]);
}

/** HPKE base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM. */
export function suite(): CipherSuite {
  return new CipherSuite({ kem: new DhkemX25519HkdfSha256(), kdf: new HkdfSha256(), aead: new Aes256Gcm() });
}

export type GrantFailure = "length" | "open" | "plaintext" | "version" | "sA";

export class GrantError extends Error {
  constructor(readonly kind: GrantFailure) {
    super(`grant refused: ${kind}`);
    this.name = "GrantError";
  }
}

/**
 * Opens a grant and checks it, in the design's order: exactly 113 bytes,
 * HPKE opening with info and an empty aad, exactly 65 bytes of plaintext
 * with version 0x01, and H("ashidori pair app v1" ‖ sA) = nA. Returns the
 * raw batch key; the caller imports it and zeroes it. `recipient` is the
 * browser's key pair (the private key stays non-extractable: the public
 * key is given so that nothing has to be derived from it).
 */
export async function openGrant(recipient: CryptoKeyPair, th: Uint8Array, nA: Uint8Array, grant: Uint8Array): Promise<Uint8Array> {
  if (grant.byteLength !== GRANT_LEN) throw new GrantError("length");
  let plain: Uint8Array;
  try {
    const ctx = await suite().createRecipientContext({ recipientKey: recipient, enc: buf(grant.slice(0, 32)).buffer, info: buf(grantInfo(th)).buffer });
    plain = new Uint8Array(await ctx.open(buf(grant.slice(32)).buffer, new ArrayBuffer(0)));
  } catch {
    throw new GrantError("open");
  }
  try {
    if (plain.byteLength !== PLAIN_LEN) throw new GrantError("plaintext");
    if (plain[0] !== GRANT_VERSION) throw new GrantError("version");
    const sA = plain.slice(1, 33);
    const ok = equalBytes(await appNonceOf(sA), nA);
    sA.fill(0);
    if (!ok) throw new GrantError("sA");
    return plain.slice(33, 65);
  } finally {
    plain.fill(0);
  }
}

/** The browser's temporary key pair: X25519, the private key not extractable. */
export async function newKeyPair(): Promise<{ keys: CryptoKeyPair; pkB: Uint8Array }> {
  const keys = (await crypto.subtle.generateKey({ name: "X25519" }, false, ["deriveBits"])) as CryptoKeyPair;
  const pkB = new Uint8Array(await crypto.subtle.exportKey("raw", keys.publicKey));
  return { keys, pkB };
}

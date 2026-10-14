// Opening sync batches (docs/sync-protocol.md, "Keys and encryption"):
//   blob = "ASH1" ‖ nonce (12) ‖ AES-256-GCM(gzip(JSON Lines)) ‖ tag (16)
//   AAD  = "{deviceId}/{stream}/{seq}"
// The order of checks follows the app (DeviceStatus download): the size and
// SHA-256 the listing gave first, then the format, decryption, gunzip under
// a cap, and JSON Lines under a line cap.

import { LimitExceeded, buf, readCapped, sha256Hex } from "./bytes";

const MAGIC = [0x41, 0x53, 0x48, 0x31]; // "ASH1"
const NONCE = 12;
const TAG = 16;
export const MIN_BLOB = MAGIC.length + NONCE + TAG;

/** Where a blob sits and what the listing said about it. */
export interface BlobRef {
  deviceId: string;
  stream: string;
  seq: number;
  size: number;
  sha256: string;
}

export type BlobFailure = "size" | "hash" | "format" | "decrypt" | "limit" | "gzip" | "parse";

export class BlobError extends Error {
  constructor(
    readonly kind: BlobFailure,
    message: string,
  ) {
    super(message);
    this.name = "BlobError";
  }
}

export interface OpenCaps {
  /** Decompressed plaintext, bytes. */
  maxPlain: number;
  /** JSON lines. */
  maxLines: number;
}

/** Caps of the app for `status` (DeviceStatus.MaxPlain, MaxLines). */
export const STATUS_CAPS: OpenCaps = { maxPlain: 64 * 1024, maxLines: 64 };
/**
 * Other streams: an editable batch holds at most 5 000 rows or 4 MiB of
 * plaintext; an archive batch one record of a 3 MiB chunk in base64.
 */
export const BATCH_CAPS: OpenCaps = { maxPlain: 8 * 1024 * 1024, maxLines: 5_000 };

/** A non-extractable AES-GCM key that can only decrypt. */
export async function importBatchKey(raw: Uint8Array): Promise<CryptoKey> {
  if (raw.byteLength !== 32) throw new Error("batch key must be 32 bytes");
  return crypto.subtle.importKey("raw", buf(raw), { name: "AES-GCM" }, false, ["decrypt"]);
}

export function aad(deviceId: string, stream: string, seq: number): Uint8Array {
  return new TextEncoder().encode(`${deviceId}/${stream}/${seq}`);
}

/** Checks the blob against the listing, then decrypts it. */
export async function decryptBlob(key: CryptoKey, ref: BlobRef, blob: Uint8Array): Promise<Uint8Array> {
  if (blob.byteLength !== ref.size) throw new BlobError("size", `size ${blob.byteLength}, listed ${ref.size}`);
  if ((await sha256Hex(blob)) !== ref.sha256.toLowerCase()) throw new BlobError("hash", "SHA-256 differs from the listing");
  if (blob.byteLength < MIN_BLOB || MAGIC.some((m, i) => blob[i] !== m)) throw new BlobError("format", "not an ASH1 blob");
  try {
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: buf(blob.slice(4, 4 + NONCE)), additionalData: buf(aad(ref.deviceId, ref.stream, ref.seq)), tagLength: 128 },
      key,
      buf(blob.subarray(4 + NONCE)),
    );
    return new Uint8Array(plain);
  } catch {
    throw new BlobError("decrypt", "decryption failed (wrong key, or the blob was moved or changed)");
  }
}

/** gunzip with DecompressionStream, aborting past `max` bytes. */
export async function gunzip(data: Uint8Array, max: number): Promise<Uint8Array> {
  const stream = new Blob([buf(data)]).stream().pipeThrough(new DecompressionStream("gzip"));
  try {
    return await readCapped(stream, max, "decompressed batch");
  } catch (e) {
    if (e instanceof LimitExceeded) throw new BlobError("limit", e.message);
    throw new BlobError("gzip", "not valid gzip");
  }
}

/** Non-empty lines as JSON values; more than `maxLines` is refused, not parsed. */
export function parseJsonLines(plain: Uint8Array, maxLines: number): unknown[] {
  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(plain);
  } catch {
    throw new BlobError("parse", "not UTF-8");
  }
  const lines = text.split("\n").map((l) => l.trim()).filter((l) => l.length > 0);
  if (lines.length > maxLines) throw new BlobError("limit", `more than ${maxLines} lines`);
  return lines.map((l) => {
    try {
      return JSON.parse(l) as unknown;
    } catch {
      throw new BlobError("parse", "a line is not JSON");
    }
  });
}

/** Verifies, decrypts, gunzips and parses one batch. */
export async function openBlob(key: CryptoKey, ref: BlobRef, blob: Uint8Array, caps: OpenCaps = BATCH_CAPS): Promise<unknown[]> {
  const gz = await decryptBlob(key, ref, blob);
  return parseJsonLines(await gunzip(gz, caps.maxPlain), caps.maxLines);
}

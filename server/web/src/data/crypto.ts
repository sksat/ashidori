// Opening sync batches (docs/sync-protocol.md, "Keys and encryption" and
// "Blob authentication"):
//   ASH1 = "ASH1" ‖ nonce (12) ‖ AES-256-GCM(gzip(JSON Lines)) ‖ tag (16)
//   ASHM = "ASHM" ‖ nonce (12) ‖ AES-256-GCM(gzip(JSON Lines)) ‖ tag (16) ‖ mac (32)
//   AAD  = "{deviceId}/{stream}/{seq}" (the same for both)
// The order of checks follows the app (DeviceStatus download): the size and
// SHA-256 the listing gave first, then the format, decryption, gunzip under
// a cap, and JSON Lines under a line cap.
//
// A viewer cannot authenticate the writer (until v2). The mac of ASHM is
// HMAC-SHA256 under Ka, a key only the user's apps hold; a viewer has the
// batch key only, so it strips the mac unchecked and opens the GCM part.
// GCM shows only that a holder of the batch key made the blob for this slot:
// an app, but also any viewer, or anyone the batch key leaked to. With the
// server, such a holder can show this page made-up records in either format
// (a forged ASHM carries a mac that nobody here checks). Apps refuse them:
// they check the mac and accept ASH1 only under the cut-over rules. In v2,
// writers sign blobs so that viewers can check them too.

import { LimitExceeded, buf, readCapped, sha256Hex } from "./bytes";

const MAGIC_V1 = [0x41, 0x53, 0x48, 0x31]; // "ASH1"
const MAGIC_AUTH = [0x41, 0x53, 0x48, 0x4d]; // "ASHM"
const NONCE = 12;
const TAG = 16;
/** Length of the ASHM mac (HMAC-SHA256), which a viewer cannot check. */
export const MAC = 32;
export const MIN_BLOB = MAGIC_V1.length + NONCE + TAG;
export const MIN_BLOB_AUTH = MIN_BLOB + MAC;

export type BlobFormat = "ASH1" | "ASHM";

/** The format by magic and minimum length; null if neither. */
export function blobFormat(blob: Uint8Array): BlobFormat | null {
  const is = (m: number[]) => blob.byteLength >= 4 && m.every((b, i) => blob[i] === b);
  if (is(MAGIC_V1) && blob.byteLength >= MIN_BLOB) return "ASH1";
  if (is(MAGIC_AUTH) && blob.byteLength >= MIN_BLOB_AUTH) return "ASHM";
  return null;
}

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

/**
 * Checks the blob against the listing, then decrypts it. Of ASHM, the mac is
 * stripped without a check (a viewer has no Ka; see the top of this file)
 * and the GCM part is opened as for ASH1.
 */
export async function decryptBlob(key: CryptoKey, ref: BlobRef, blob: Uint8Array): Promise<Uint8Array> {
  if (blob.byteLength !== ref.size) throw new BlobError("size", `size ${blob.byteLength}, listed ${ref.size}`);
  if ((await sha256Hex(blob)) !== ref.sha256.toLowerCase()) throw new BlobError("hash", "SHA-256 differs from the listing");
  const format = blobFormat(blob);
  if (format === null) throw new BlobError("format", "not an ASH1 or ASHM blob");
  const end = format === "ASHM" ? blob.byteLength - MAC : blob.byteLength;
  try {
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: buf(blob.slice(4, 4 + NONCE)), additionalData: buf(aad(ref.deviceId, ref.stream, ref.seq)), tagLength: 128 },
      key,
      buf(blob.subarray(4 + NONCE, end)),
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

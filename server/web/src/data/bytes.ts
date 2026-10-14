// Byte helpers: hex, base64, SHA-256 and reading a body under a size cap.

export function hex(b: Uint8Array): string {
  let s = "";
  for (const x of b) s += x.toString(16).padStart(2, "0");
  return s;
}

export function fromHex(s: string): Uint8Array {
  if (!/^(?:[0-9a-fA-F]{2})*$/.test(s)) throw new Error("not hex");
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(s.slice(2 * i, 2 * i + 2), 16);
  return out;
}

/** Standard or URL-safe base64, with or without padding. */
export function fromBase64(s: string): Uint8Array {
  const std = s.replace(/-/g, "+").replace(/_/g, "/").replace(/\s+/g, "");
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(std)) throw new Error("not base64");
  const padded = std + "=".repeat((4 - (std.length % 4)) % 4);
  const bin = atob(padded);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** A plain ArrayBuffer view, as WebCrypto wants (not a SharedArrayBuffer). */
export function buf(b: Uint8Array): Uint8Array<ArrayBuffer> {
  return b.buffer instanceof ArrayBuffer && b.byteOffset === 0 && b.byteLength === b.buffer.byteLength
    ? (b as Uint8Array<ArrayBuffer>)
    : new Uint8Array(b);
}

export async function sha256Hex(b: Uint8Array): Promise<string> {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", buf(b))));
}

export class LimitExceeded extends Error {
  constructor(
    readonly what: string,
    readonly max: number,
  ) {
    super(`${what} exceeds ${max} bytes`);
    this.name = "LimitExceeded";
  }
}

/** Reads a stream to the end, aborting as soon as more than `max` bytes arrive. */
export async function readCapped(stream: ReadableStream<Uint8Array>, max: number, what: string): Promise<Uint8Array> {
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let n = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      n += value.byteLength;
      if (n > max) {
        await reader.cancel().catch(() => {});
        throw new LimitExceeded(what, max);
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const out = new Uint8Array(n);
  let o = 0;
  for (const c of chunks) {
    out.set(c, o);
    o += c.byteLength;
  }
  return out;
}

export function concat(parts: readonly Uint8Array[]): Uint8Array {
  const n = parts.reduce((a, p) => a + p.byteLength, 0);
  const out = new Uint8Array(n);
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.byteLength;
  }
  return out;
}

/** URL-safe base64 without padding (the API's byte fields, device tokens). */
export function toBase64Url(b: Uint8Array): string {
  let bin = "";
  for (const x of b) bin += String.fromCharCode(x);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Strict URL-safe base64 without padding of exactly `len` bytes, else null. */
export function fromBase64UrlExact(s: unknown, len: number): Uint8Array | null {
  if (typeof s !== "string" || !/^[A-Za-z0-9_-]*$/.test(s) || s.length !== Math.ceil((len * 4) / 3)) return null;
  try {
    const b = fromBase64(s);
    // Non-canonical encodings (stray bits in the last character) are refused.
    return b.byteLength === len && toBase64Url(b) === s ? b : null;
  } catch {
    return null;
  }
}

/** Comparison of two byte strings without an early exit (their length is public). */
export function equalBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.byteLength !== b.byteLength) return false;
  let d = 0;
  for (let i = 0; i < a.byteLength; i++) d |= a[i]! ^ b[i]!;
  return d === 0;
}

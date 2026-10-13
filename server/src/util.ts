import type { Context } from "hono";
import type { ContentfulStatusCode } from "hono/utils/http-status";

const encoder = new TextEncoder();

export const HOUR_MS = 60 * 60 * 1000;

/**
 * Rounds a time down to the hour. Times the server keeps only for display
 * (upload, last seen) are stored this coarsely, so that they reveal little
 * about when a user is active.
 */
export function floorHour(ms: number): number {
  return ms - (ms % HOUR_MS);
}

export function jsonError(c: Context, status: ContentfulStatusCode, error: string): Response {
  return c.json({ error }, status);
}

export function toHex(bytes: ArrayBuffer | Uint8Array): string {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let out = "";
  for (const b of view) out += b.toString(16).padStart(2, "0");
  return out;
}

export async function sha256Hex(data: string | ArrayBuffer | Uint8Array): Promise<string> {
  const input = typeof data === "string" ? encoder.encode(data) : data;
  return toHex(await crypto.subtle.digest("SHA-256", input));
}

/** Constant-time comparison of two strings (length is not secret). */
export function timingSafeEqualStr(a: string, b: string): boolean {
  const ab = encoder.encode(a);
  const bb = encoder.encode(b);
  if (ab.byteLength !== bb.byteLength) return false;
  // workerd provides crypto.subtle.timingSafeEqual.
  return crypto.subtle.timingSafeEqual(ab, bb);
}

export function base64UrlEncode(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function base64UrlDecode(s: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]*$/.test(s)) throw new Error("invalid base64url");
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (s.length % 4)) % 4);
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function randomBytes(n: number): Uint8Array {
  const b = new Uint8Array(n);
  crypto.getRandomValues(b);
  return b;
}

// ---- Crockford base32 pairing codes ----

const CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/** 8 Crockford base32 characters = 40 random bits. */
export function generatePairingCode(): string {
  const bytes = randomBytes(5);
  let bits = 0n;
  for (const b of bytes) bits = (bits << 8n) | BigInt(b);
  let out = "";
  for (let i = 7; i >= 0; i--) out += CROCKFORD[Number((bits >> BigInt(i * 5)) & 31n)];
  return out;
}

/**
 * Normalizes user input per Crockford's rules: case-insensitive, I/L -> 1, O -> 0,
 * hyphens and spaces ignored. Returns null if the result is not 8 valid characters.
 */
export function normalizePairingCode(input: string): string | null {
  const s = input
    .toUpperCase()
    .replace(/[\s-]/g, "")
    .replace(/[IL]/g, "1")
    .replace(/O/g, "0");
  if (!/^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{8}$/.test(s)) return null;
  return s;
}

export const DEVICE_TOKEN_RE = /^[A-Za-z0-9_-]{43}$/;

// ---- Request bodies ----

const MAX_JSON_BYTES = 4 * 1024;

export class TooLarge extends Error {}

/** Reads the request body, aborting as soon as it exceeds `max` bytes. */
export async function readBody(req: Request, max: number): Promise<Uint8Array> {
  const declared = req.headers.get("Content-Length");
  if (declared !== null && Number(declared) > max) throw new TooLarge();
  if (!req.body) return new Uint8Array(0);
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel();
      throw new TooLarge();
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let off = 0;
  for (const ch of chunks) {
    out.set(ch, off);
    off += ch.byteLength;
  }
  return out;
}

export async function readJson(req: Request): Promise<unknown> {
  const ct = req.headers.get("Content-Type") ?? "";
  if (!/^application\/json\b/i.test(ct)) throw new SyntaxError("content-type");
  const body = await readBody(req, MAX_JSON_BYTES);
  return JSON.parse(new TextDecoder().decode(body));
}

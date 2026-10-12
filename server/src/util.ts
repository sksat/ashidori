import type { Context } from "hono";
import type { ContentfulStatusCode } from "hono/utils/http-status";

const encoder = new TextEncoder();

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

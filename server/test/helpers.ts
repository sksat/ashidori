import { createExecutionContext, waitOnExecutionContext } from "cloudflare:test";
import { env } from "cloudflare:workers";
import app from "../src/index";

export const ADMIN_DEV_TOKEN = "dev-admin-token-for-tests";
export const BASE = "https://ashidori.example";

export async function call(
  path: string,
  init: RequestInit = {},
  envOverrides: Partial<Cloudflare.Env> = {},
): Promise<Response> {
  const ctx = createExecutionContext();
  const res = await app.fetch(new Request(BASE + path, init), { ...env, ...envOverrides }, ctx);
  await waitOnExecutionContext(ctx);
  return res;
}

export function adminCall(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${ADMIN_DEV_TOKEN}`);
  return call(path, { ...init, headers });
}

export async function issueAdminCode(): Promise<string> {
  const res = await adminCall("/admin/pairing", { method: "POST" });
  if (res.status !== 201) throw new Error(`pairing failed: ${res.status}`);
  return ((await res.json()) as { code: string }).code;
}

export function register(code: string, name = "pixel", ip = "192.0.2.1"): Promise<Response> {
  return call("/v1/devices/register", {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": ip },
    body: JSON.stringify({ code, name }),
  });
}

export async function newDevice(name = "pixel"): Promise<{ deviceId: string; token: string }> {
  const res = await register(await issueAdminCode(), name);
  if (res.status !== 201) throw new Error(`register failed: ${res.status}`);
  return (await res.json()) as { deviceId: string; token: string };
}

export function authed(token: string, path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${token}`);
  return call(path, { ...init, headers });
}

export async function sha256Hex(data: Uint8Array): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", data));
  return [...d].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function putBatch(
  token: string,
  stream: string,
  seq: number | string,
  body: Uint8Array,
  hash?: string,
): Promise<Response> {
  return authed(token, `/v1/batches/${stream}/${seq}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/octet-stream",
      "X-Content-SHA256": hash ?? (await sha256Hex(body)),
    },
    body,
  });
}

export function blob(text: string): Uint8Array {
  return new TextEncoder().encode(text);
}

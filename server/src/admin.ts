import { Hono } from "hono";
import { adminAuth } from "./auth";
import { createPairingCode, listDevices, revokeDevice } from "./devices";
import type { AppEnv } from "./env";
import { clientKey, hitRateLimit, RULES } from "./ratelimit";
import { base64UrlEncode, jsonError, randomBytes } from "./util";

export const admin = new Hono<AppEnv>();

admin.use("*", adminAuth);

admin.post("/pairing", async (c) => {
  if (!(await hitRateLimit(c.env.DB, RULES.pairing, clientKey(c.req.raw)))) {
    return jsonError(c, 429, "too many requests");
  }
  return c.json(await createPairingCode(c.env.DB, "admin"), 201);
});

admin.get("/devices", async (c) => c.json(await listDevices(c.env.DB)));

admin.post("/devices/:id/revoke", async (c) => {
  if (!(await revokeDevice(c.env.DB, c.req.param("id")))) return jsonError(c, 404, "no such device");
  return c.json({ revoked: true });
});

// Minimal admin page (behind Access) to issue pairing codes and manage devices.
admin.get("/", (c) => {
  const nonce = base64UrlEncode(randomBytes(16));
  c.header(
    "Content-Security-Policy",
    `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`,
  );
  return c.html(`<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ashidori admin</title>
<style nonce="${nonce}">
body{font-family:system-ui,sans-serif;max-width:40rem;margin:2rem auto;padding:0 1rem}
#code{font:700 2rem ui-monospace,monospace;letter-spacing:.2em}
table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #ccc;padding:.3rem;text-align:left}
</style></head><body>
<h1>ashidori admin</h1>
<section><h2>ペアリングコード</h2>
<button id="issue">発行する</button>
<p id="code"></p><p id="exp"></p></section>
<section><h2>デバイス</h2><table><thead><tr><th>名前</th><th>作成</th><th>最終アクセス</th><th></th></tr></thead><tbody id="devices"></tbody></table></section>
<script nonce="${nonce}">
const fmt = (t) => t == null ? "-" : new Date(t).toLocaleString();
async function load() {
  const res = await fetch("/admin/devices");
  const tbody = document.getElementById("devices");
  tbody.textContent = "";
  for (const d of await res.json()) {
    const tr = document.createElement("tr");
    for (const v of [d.name + (d.revoked ? " (revoked)" : ""), fmt(d.createdAt), fmt(d.lastSeenAt)]) {
      const td = document.createElement("td"); td.textContent = v; tr.appendChild(td);
    }
    const td = document.createElement("td");
    if (!d.revoked) {
      const b = document.createElement("button"); b.textContent = "revoke";
      b.onclick = async () => {
        if (!confirm("revoke " + d.name + "?")) return;
        await fetch("/admin/devices/" + encodeURIComponent(d.id) + "/revoke", { method: "POST" });
        load();
      };
      td.appendChild(b);
    }
    tr.appendChild(td); tbody.appendChild(tr);
  }
}
document.getElementById("issue").onclick = async () => {
  const res = await fetch("/admin/pairing", { method: "POST" });
  const body = await res.json();
  if (!res.ok) { document.getElementById("code").textContent = body.error; return; }
  document.getElementById("code").textContent = body.code.slice(0, 4) + "-" + body.code.slice(4);
  document.getElementById("exp").textContent = "有効期限: " + fmt(body.expiresAt);
};
load();
</script></body></html>`);
});

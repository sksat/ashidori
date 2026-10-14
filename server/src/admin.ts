import { Hono } from "hono";
import { registrationMode, SELF_REGISTERED, serverUsage } from "./accounts";
import { adminAuth } from "./auth";
import { listRecentCheckins, removeCheckin } from "./checkins";
import { parseLimits } from "./config";
import { createPairingCode, listAllDevices, revokeDevice } from "./devices";
import type { AppEnv } from "./env";
import { QR_SCRIPT } from "./qr";
import { clientKey, edgeAllowed, hitRateLimit, RULES, selfRegistrationEdgeReady } from "./ratelimit";
import {
  createUser,
  getUser,
  HandleTaken,
  listUsers,
  parseNewUser,
  parseUserPatch,
  setUserStatus,
  updateUser,
} from "./users";
import { base64UrlEncode, jsonError, randomBytes, readJson, TooLarge } from "./util";

// Operator API. Every route requires an admin (see adminAuth), which also
// rejects cross-origin requests. POST bodies, where present, must be JSON,
// which a cross-site form cannot send without a CORS preflight.
export const admin = new Hono<AppEnv>();

admin.use("*", adminAuth);

// ---- Users ----

admin.get("/users", async (c) => c.json(await listUsers(c.env.DB)));

admin.post("/users", async (c) => {
  let body: unknown;
  try {
    body = await readJson(c.req.raw);
  } catch (e) {
    if (e instanceof TooLarge) return jsonError(c, 413, "body too large");
    return jsonError(c, 400, "invalid JSON body");
  }
  const parsed = parseNewUser(body);
  if (typeof parsed === "string") return jsonError(c, 400, parsed);
  // Like every creation, refused while a limit variable is invalid (fail closed).
  const limits = parseLimits(c.env);
  if (!limits.ok) {
    console.error("invalid limit variables; refusing new users:", limits.invalid.join(", "));
    return jsonError(c, 503, "server misconfigured");
  }
  try {
    return c.json(await createUser(c.env.DB, parsed, c.get("adminEmail")), 201);
  } catch (e) {
    if (e instanceof HandleTaken) return jsonError(c, 409, "handle already taken");
    throw e;
  }
});

// `{name?, handle?}`; `handle: null` removes the handle.
admin.patch("/users/:id", async (c) => {
  let body: unknown;
  try {
    body = await readJson(c.req.raw);
  } catch (e) {
    if (e instanceof TooLarge) return jsonError(c, 413, "body too large");
    return jsonError(c, 400, "invalid JSON body");
  }
  const parsed = parseUserPatch(body);
  if (typeof parsed === "string") return jsonError(c, 400, parsed);
  try {
    const user = await updateUser(c.env.DB, c.req.param("id"), parsed);
    return user ? c.json(user) : jsonError(c, 404, "no such user");
  } catch (e) {
    if (e instanceof HandleTaken) return jsonError(c, 409, "handle already taken");
    throw e;
  }
});

// A pairing code for the user's device; the user enters it in the app.
admin.post("/users/:id/pairing", async (c) => {
  if (!(await edgeAllowed(c.env.RL_PAIRING, "RL_PAIRING", `pairing:admin:${c.get("adminEmail")}`))) {
    return jsonError(c, 429, "too many requests");
  }
  if (!(await hitRateLimit(c.env.DB, RULES.pairing, clientKey(c.req.raw)))) {
    return jsonError(c, 429, "too many requests");
  }
  const user = await getUser(c.env.DB, c.req.param("id"));
  if (!user) return jsonError(c, 404, "no such user");
  if (user.status !== "active") return jsonError(c, 409, "user is disabled");
  return c.json(await createPairingCode(c.env.DB, `admin:${c.get("adminEmail")}`, user.id, null), 201);
});

admin.post("/users/:id/disable", async (c) => {
  const user = await setUserStatus(c.env.DB, c.req.param("id"), "disabled");
  return user ? c.json(user) : jsonError(c, 404, "no such user");
});

admin.post("/users/:id/enable", async (c) => {
  const user = await setUserStatus(c.env.DB, c.req.param("id"), "active");
  return user ? c.json(user) : jsonError(c, 404, "no such user");
});

// ---- Devices (all users, including legacy devices without a user) ----

admin.get("/devices", async (c) => c.json(await listAllDevices(c.env.DB)));

admin.post("/devices/:id/revoke", async (c) => {
  if (!(await revokeDevice(c.env.DB, c.req.param("id"), "all"))) return jsonError(c, 404, "no such device");
  return c.json({ revoked: true });
});

// ---- Shared check-ins (moderation) ----
// Shared check-ins are plaintext by design (see checkins.ts); everything else
// users store is ciphertext the admin cannot read.

const ADMIN_CHECKINS_LIMIT = 200;

admin.get("/checkins", async (c) => c.json(await listRecentCheckins(c.env.DB, ADMIN_CHECKINS_LIMIT)));

admin.post("/checkins/:id/delete", async (c) => {
  if (!(await removeCheckin(c.env.DB, c.req.param("id")))) return jsonError(c, 404, "no such check-in");
  return c.json({ deleted: true });
});

// Minimal admin page (behind Access) to manage users and devices and to
// moderate shared check-ins.
admin.get("/", async (c) => {
  const nonce = base64UrlEncode(randomBytes(16));
  // All server-generated (fixed strings and numbers), safe to embed.
  const modeText =
    registrationMode(c.env) === "open"
      ? "新規登録: 公開（誰でもアプリから利用者を作成できる）"
      : "新規登録: 管理者のみ（この画面で利用者を作成する）";
  const parsed = parseLimits(c.env);
  const limits = parsed.ok ? parsed.limits : null;
  const quota = limits ? limits.userQuotaBytes : null;
  const usage = await serverUsage(c.env.DB);
  const misconfig: string[] = [];
  if (!parsed.ok) {
    misconfig.push(`設定が不正なため、新しい利用者・デバイス・データの作成を停止しています: ${parsed.invalid.join(", ")}`);
  }
  if (registrationMode(c.env) === "open" && !selfRegistrationEdgeReady(c.env)) {
    misconfig.push("レート制限の binding RL_ACCOUNT がないため、アプリからの新規登録を停止しています。");
  }
  const limitsText = misconfig.map((m) => `<p class="misconfig">${m}</p>`).join("");
  c.header(
    "Content-Security-Policy",
    `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`,
  );
  return c.html(`<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ashidori admin</title>
<style nonce="${nonce}">
body{font-family:system-ui,sans-serif;max-width:48rem;margin:2rem auto;padding:0 1rem}
#code{font:700 2rem ui-monospace,monospace;letter-spacing:.2em;margin:.2rem 0}
#pairing[hidden]{display:none}#pairing{border:1px solid #ccc;padding:.5rem 1rem;margin:1rem 0}#link{word-break:break-all}
#pairing .row{display:flex;flex-wrap:wrap;gap:1rem;align-items:center}#pairing .row>div{flex:1 1 16rem}
#qr svg{display:block;width:12rem;height:12rem}
table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #ccc;padding:.3rem;text-align:left}
td button{margin-right:.3rem}#err,.misconfig{color:#b00}
</style></head><body>
<h1>ashidori admin</h1>
<p id="err"></p>
<section><h2>利用者</h2>
${limitsText}
<p>${modeText}。容量の上限は利用者ごとに <span id="quota"></span> です。</p>
<p>サーバ全体: 利用者 ${usage.userCount}${limits ? ` / ${limits.maxUsers}（自己登録の上限）` : ""}、保存量 <span id="total"></span></p>
<p><input id="name" placeholder="名前" maxlength="64"> <input id="handle" placeholder="ハンドル (任意)" maxlength="20"> <input id="email" type="email" placeholder="メールアドレス (任意)"> <button id="create">作成</button></p>
<p>ハンドルは英小文字・数字・_ の 3〜20 文字です。他の利用者はハンドルの完全一致で検索し、フォローを申請します。</p>
<div id="pairing" hidden><div class="row"><div id="qr"></div><div><p id="for"></p><p id="code"></p><p id="exp"></p>
<p>登録リンク: <code id="link"></code> <button id="copy">コピー</button></p></div></div>
<p>利用者がアプリの「QR コードを読み取る」でこの QR コードを読み取るか、アプリのある端末でリンクを開くと、サーバとコードが入力されます。2 台目以降の端末では、登録後に復旧コードを入力します。復旧コードは QR コードとリンクに含まれません。</p></div>
<table><thead><tr><th>名前</th><th>ハンドル</th><th>メールアドレス</th><th>作成</th><th>状態</th><th>デバイス</th><th>使用量</th><th>最終アクセス</th><th></th></tr></thead><tbody id="users"></tbody></table></section>
<section><h2>デバイス</h2>
<p>利用者が「なし」のデバイスは、利用者の導入前に登録されたものです。</p>
<table><thead><tr><th>名前</th><th>利用者</th><th>作成</th><th>最終アクセス</th><th></th></tr></thead><tbody id="devices"></tbody></table></section>
<section><h2>共有チェックイン</h2>
<p>利用者がフォロワーに共有したチェックインです (新しい順、最大 200 件)。</p>
<table><thead><tr><th>日時</th><th>利用者</th><th>場所</th><th>カテゴリ</th><th>コメント</th><th></th></tr></thead><tbody id="checkins"></tbody></table></section>
<script nonce="${nonce}">${QR_SCRIPT}</script>
<script nonce="${nonce}">
const fmt = (t) => t == null ? "-" : new Date(t).toLocaleString();
const $ = (id) => document.getElementById(id);
const QUOTA = ${quota};
const TOTAL = ${usage.totalBytes};
const MAX_TOTAL = ${limits ? limits.maxTotalBytes : null};
function fmtBytes(n) {
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let i = 0;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return (i === 0 ? String(n) : n.toFixed(1)) + " " + units[i];
}
$("quota").textContent = QUOTA === null ? "（設定が不正）" : fmtBytes(QUOTA);
$("total").textContent = fmtBytes(TOTAL) + (MAX_TOTAL === null ? "" : " / " + fmtBytes(MAX_TOTAL));
async function api(path, body, method = "POST") {
  const init = { method };
  if (body !== undefined) { init.headers = { "Content-Type": "application/json" }; init.body = JSON.stringify(body); }
  const res = await fetch(path, init);
  const json = await res.json();
  $("err").textContent = res.ok ? "" : "エラー: " + json.error;
  return res.ok ? json : null;
}
function row(tbody, values, buttons) {
  const tr = document.createElement("tr");
  for (const v of values) { const td = document.createElement("td"); td.textContent = v; tr.appendChild(td); }
  const td = document.createElement("td");
  for (const [label, onclick] of buttons) {
    const b = document.createElement("button"); b.textContent = label; b.onclick = onclick; td.appendChild(b);
  }
  tr.appendChild(td); tbody.appendChild(tr);
}
async function load() {
  const [users, devices, checkins] = await Promise.all([
    fetch("/admin/users").then((r) => r.json()),
    fetch("/admin/devices").then((r) => r.json()),
    fetch("/admin/checkins").then((r) => r.json()),
  ]);
  const ut = $("users"); ut.textContent = "";
  for (const u of users) {
    const active = u.status === "active";
    const buttons = active
      ? [["ペアリングコード発行", () => issue(u)],
         ["ハンドル変更", async () => {
           const h = prompt(u.name + " のハンドル (空欄で削除)", u.handle ?? "");
           if (h !== null && await api("/admin/users/" + encodeURIComponent(u.id), { handle: h.trim() || null }, "PATCH")) load();
         }],
         ["停止", async () => { if (confirm(u.name + " を停止しますか。この利用者のデバイスは同期できなくなります。") && await api("/admin/users/" + encodeURIComponent(u.id) + "/disable")) load(); }]]
      : [["再開", async () => { if (await api("/admin/users/" + encodeURIComponent(u.id) + "/enable")) load(); }]];
    const createdBy = u.createdBy === ${JSON.stringify(SELF_REGISTERED)} ? "自己登録" : "管理者";
    const used = fmtBytes(u.storageBytes) + (QUOTA === null ? "" : " (" + Math.floor((u.storageBytes / QUOTA) * 100) + "%)");
    row(ut, [u.name, u.handle ?? "-", u.email ?? "-", createdBy, active ? "有効" : "停止中", String(u.deviceCount), used, fmt(u.lastSeenAt)], buttons);
  }
  const dt = $("devices"); dt.textContent = "";
  for (const d of devices) {
    const buttons = d.revoked ? [] : [["失効", async () => {
      if (confirm(d.name + " を失効させますか。") && await api("/admin/devices/" + encodeURIComponent(d.id) + "/revoke")) load();
    }]];
    row(dt, [d.name + (d.revoked ? " (失効)" : ""), d.userName ?? "なし", fmt(d.createdAt), fmt(d.lastSeenAt)], buttons);
  }
  const ct = $("checkins"); ct.textContent = "";
  for (const p of checkins) {
    const where = p.venue + (p.place ? " (" + p.place + ")" : "");
    row(ct, [fmt(p.t), p.user.name + (p.userStatus === "active" ? "" : " (停止中)"), where, p.category ?? "-", p.comment ?? "-"], [["削除", async () => {
      if (confirm(where + " を削除しますか。フォロワーの一覧から消えます。") && await api("/admin/checkins/" + encodeURIComponent(p.id) + "/delete")) load();
    }]]);
  }
}
async function issue(u) {
  const body = await api("/admin/users/" + encodeURIComponent(u.id) + "/pairing");
  if (!body) return;
  $("pairing").hidden = false;
  $("for").textContent = u.name + " のペアリングコード";
  $("code").textContent = body.code.slice(0, 4) + "-" + body.code.slice(4);
  $("exp").textContent = "有効期限: " + fmt(body.expiresAt) + " (1 回のみ使用可)";
  // The code goes in the fragment, which browsers never send to the server.
  const link = location.origin + "/pair#c=" + body.code;
  $("link").textContent = link;
  showQr($("qr"), link);
}
$("copy").onclick = async () => {
  try {
    await navigator.clipboard.writeText($("link").textContent);
    $("copy").textContent = "コピーしました";
  } catch {
    $("copy").textContent = "コピーできませんでした";
  }
  setTimeout(() => { $("copy").textContent = "コピー"; }, 2000);
};
$("create").onclick = async () => {
  const email = $("email").value.trim();
  const handle = $("handle").value.trim();
  const body = { name: $("name").value.trim() };
  if (email) body.email = email;
  if (handle) body.handle = handle;
  if (await api("/admin/users", body)) { $("name").value = ""; $("handle").value = ""; $("email").value = ""; load(); }
};
load();
</script></body></html>`);
});

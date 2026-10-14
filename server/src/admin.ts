import { Hono } from "hono";
import { INVITED_PREFIX, registrationMode, SELF_REGISTERED, serverUsage } from "./accounts";
import { adminAuth } from "./auth";
import { listRecentCheckins, removeCheckin } from "./checkins";
import { parseLimits } from "./config";
import { createPairingCode, listAllDevices, revokeDevice } from "./devices";
import type { AppEnv } from "./env";
import { createInvite, INVITE_DEFAULT_DAYS, listInvites, parseInviteDays, revokeInvite } from "./invites";
import { QR_SCRIPT } from "./qr";
import { clientKey, edgeAllowed, hitRateLimit, RULES, selfRegistrationEdgeReady } from "./ratelimit";
import { getUser, listUsers, parseUsernamePatch, setUserStatus, setUsername, UsernameTaken } from "./users";
import { base64UrlEncode, jsonError, randomBytes, readJson, TooLarge } from "./util";

// Operator API. Every route requires an admin (see adminAuth), which also
// rejects cross-origin requests. POST bodies, where present, must be JSON,
// which a cross-site form cannot send without a CORS preflight.
//
// Admins do not create users: they create invites (invites.ts), and the
// person creates their user on their first app device with a username of
// their choice. Admins set the username of legacy users that have none.
export const admin = new Hono<AppEnv>();

admin.use("*", adminAuth);

async function body(c: { req: { raw: Request } }): Promise<{ value: unknown } | { status: 400 | 413; error: string }> {
  try {
    return { value: await readJson(c.req.raw) };
  } catch (e) {
    if (e instanceof TooLarge) return { status: 413, error: "body too large" };
    return { status: 400, error: "invalid JSON body" };
  }
}

// ---- Invites ----

admin.get("/invites", async (c) => c.json(await listInvites(c.env.DB)));

// `{ttlDays?}` (1-30, default 7) → `{id, code, expiresAt, link}`. The code is
// returned only here; the server keeps its hash.
admin.post("/invites", async (c) => {
  const b = await body(c);
  if ("error" in b) return jsonError(c, b.status, b.error);
  const days = parseInviteDays(b.value);
  if (days === null) return jsonError(c, 400, "invalid ttlDays");
  const invite = await createInvite(c.env.DB, `admin:${c.get("adminEmail")}`, days);
  // The code goes in the fragment, which browsers never send to the server.
  const link = `${new URL(c.req.url).origin}/pair#i=${invite.code}`;
  return c.json({ ...invite, link }, 201);
});

admin.post("/invites/:id/revoke", async (c) => {
  const r = await revokeInvite(c.env.DB, c.req.param("id"));
  if (r === null) return jsonError(c, 404, "no such invite");
  if (r === "used") return jsonError(c, 409, "invite already used");
  return c.json({ revoked: true });
});

// ---- Users ----

admin.get("/users", async (c) => c.json(await listUsers(c.env.DB)));

// `{username}`: sets the username of a legacy user, or changes it.
admin.patch("/users/:id", async (c) => {
  const b = await body(c);
  if ("error" in b) return jsonError(c, b.status, b.error);
  const parsed = parseUsernamePatch(b.value);
  if (typeof parsed === "string") return jsonError(c, 400, parsed);
  try {
    const user = await setUsername(c.env.DB, c.req.param("id"), parsed.username);
    return user ? c.json(user) : jsonError(c, 404, "no such user");
  } catch (e) {
    if (e instanceof UsernameTaken) return jsonError(c, 409, "username taken");
    throw e;
  }
});

// A pairing code for a device of an existing user (for example a legacy
// user, or one who lost every device); the user enters it in the app.
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

// Minimal admin page (behind Access): invites, users, devices and the
// moderation of shared check-ins. Copy follows docs/web-ui.md (文言).
admin.get("/", async (c) => {
  const nonce = base64UrlEncode(randomBytes(16));
  // All server-generated (fixed strings and numbers), safe to embed.
  const modeText =
    registrationMode(c.env) === "open"
      ? "登録の方式: 招待または自由な登録（招待がなくてもアプリから登録できます）"
      : "登録の方式: 招待のみ";
  const parsed = parseLimits(c.env);
  const limits = parsed.ok ? parsed.limits : null;
  const quota = limits ? limits.userQuotaBytes : null;
  const usage = await serverUsage(c.env.DB);
  const misconfig: string[] = [];
  if (!parsed.ok) {
    misconfig.push(`設定が不正なため、利用者・端末・データの新規作成を停止しています: ${parsed.invalid.join(", ")}`);
  }
  if (registrationMode(c.env) === "open" && !selfRegistrationEdgeReady(c.env)) {
    misconfig.push("レート制限の binding RL_ACCOUNT がないため、招待のない登録を停止しています。");
  }
  const limitsText = misconfig.map((m) => `<p class="misconfig">${m}</p>`).join("");
  c.header(
    "Content-Security-Policy",
    `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`,
  );
  return c.html(`<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ashidori 管理</title>
<style nonce="${nonce}">
body{font-family:system-ui,sans-serif;max-width:52rem;margin:2rem auto;padding:0 1rem}
#code{font:700 1.6rem ui-monospace,monospace;letter-spacing:.1em;margin:.2rem 0;word-break:break-all}
#issued[hidden]{display:none}#issued{border:1px solid #ccc;padding:.5rem 1rem;margin:1rem 0}#link{word-break:break-all}
#issued .row{display:flex;flex-wrap:wrap;gap:1rem;align-items:center}#issued .row>div{flex:1 1 16rem}
#qr svg{display:block;width:12rem;height:12rem}
table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #ccc;padding:.3rem;text-align:left}
td button{margin-right:.3rem}#err,.misconfig,.todo{color:#b00}.note{color:#555;font-size:.9rem}
</style></head><body>
<h1>ashidori 管理</h1>
<p id="err"></p>
${limitsText}
<section><h2>招待</h2>
<p>${modeText}</p>
<p><label>有効期限 <select id="ttl"><option value="1">1 日</option><option value="${INVITE_DEFAULT_DAYS}" selected>${INVITE_DEFAULT_DAYS} 日</option><option value="30">30 日</option></select></label> <button id="invite">招待を作成</button></p>
<p class="note">招待は 1 回限りの登録リンクです。招待された人がアプリで開き、ユーザ名を決めて登録します。</p>
<div id="issued" hidden><div class="row"><div id="qr"></div><div><p id="for"></p><p id="code"></p><p id="exp"></p>
<p>登録リンク: <code id="link"></code> <button id="copy">コピー</button></p></div></div>
<p id="howto" class="note"></p></div>
<table><thead><tr><th>作成</th><th>有効期限</th><th>状態</th><th></th></tr></thead><tbody id="invites"></tbody></table></section>
<section><h2>利用者</h2>
<p id="todo" class="todo"></p>
<p>利用者 ${usage.userCount}${limits ? `（招待のない登録の上限 ${limits.maxUsers}）` : ""}、保存量 <span id="total"></span>、容量の上限は利用者ごとに <span id="quota"></span></p>
<table><thead><tr><th>ユーザ名</th><th>登録</th><th>状態</th><th>端末</th><th>使用量</th><th>最終アクセス</th><th></th></tr></thead><tbody id="users"></tbody></table></section>
<section><h2>端末</h2>
<p class="note">利用者が「なし」の端末は、利用者の導入前に登録されたものです。</p>
<table><thead><tr><th>名前</th><th>利用者</th><th>登録</th><th>最終アクセス</th><th></th></tr></thead><tbody id="devices"></tbody></table></section>
<section><h2>共有チェックイン</h2>
<p class="note">利用者がフォロワーに共有したチェックインです（新しい順、最大 200 件）。</p>
<table><thead><tr><th>日時</th><th>利用者</th><th>場所</th><th>カテゴリ</th><th>コメント</th><th></th></tr></thead><tbody id="checkins"></tbody></table></section>
<script nonce="${nonce}">${QR_SCRIPT}</script>
<script nonce="${nonce}">
const fmt = (t) => t == null ? "-" : new Date(t).toLocaleString();
const $ = (id) => document.getElementById(id);
const QUOTA = ${quota};
const TOTAL = ${usage.totalBytes};
const MAX_TOTAL = ${limits ? limits.maxTotalBytes : null};
const SELF = ${JSON.stringify(SELF_REGISTERED)};
const INVITED = ${JSON.stringify(INVITED_PREFIX)};
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
// A user as the page names them: the username, or for a legacy user without one, what identifies them.
function who(u) {
  if (u.username) return u.username;
  const hint = [u.legacyName, u.email].filter((x) => x).join("、");
  return "未設定" + (hint ? "（" + hint + "）" : "");
}
async function setName(u) {
  const v = prompt((u.username ?? who(u)) + " のユーザ名（英小文字・数字・_ の 3〜20 文字）", u.username ?? "");
  if (v !== null && await api("/admin/users/" + encodeURIComponent(u.id), { username: v.trim() }, "PATCH")) load();
}
const INVITE_STATUS = { pending: "未使用", used: "使用済み", expired: "期限切れ", revoked: "取り消し済み" };
async function load() {
  const [invites, users, devices, checkins] = await Promise.all([
    fetch("/admin/invites").then((r) => r.json()),
    fetch("/admin/users").then((r) => r.json()),
    fetch("/admin/devices").then((r) => r.json()),
    fetch("/admin/checkins").then((r) => r.json()),
  ]);
  const it = $("invites"); it.textContent = "";
  for (const i of invites) {
    const status = INVITE_STATUS[i.status] + (i.user ? "（" + (i.user.username ?? "ユーザ名未設定") + "）" : "");
    const buttons = i.status === "pending" ? [["取り消す", async () => {
      if (confirm("この招待を取り消しますか。登録リンクは使えなくなります。") && await api("/admin/invites/" + encodeURIComponent(i.id) + "/revoke")) load();
    }]] : [];
    row(it, [fmt(i.createdAt), fmt(i.expiresAt), status], buttons);
  }
  const missing = users.filter((u) => !u.username).length;
  $("todo").textContent = missing === 0 ? "" : "ユーザ名が未設定の利用者が " + missing + " 人います。「ユーザ名を設定」で設定してください。";
  const ut = $("users"); ut.textContent = "";
  for (const u of users) {
    const active = u.status === "active";
    const buttons = active
      ? [["ペアリングコード発行", () => issuePairing(u)],
         [u.username ? "ユーザ名を変更" : "ユーザ名を設定", () => setName(u)],
         ["停止", async () => { if (confirm(who(u) + " を停止しますか。この利用者の端末は同期できなくなります。") && await api("/admin/users/" + encodeURIComponent(u.id) + "/disable")) load(); }]]
      : [["再開", async () => { if (await api("/admin/users/" + encodeURIComponent(u.id) + "/enable")) load(); }]];
    const how = u.createdBy === SELF ? "自由な登録" : u.createdBy.startsWith(INVITED) ? "招待" : "管理者";
    const used = fmtBytes(u.storageBytes) + (QUOTA === null ? "" : " (" + Math.floor((u.storageBytes / QUOTA) * 100) + "%)");
    row(ut, [who(u), how, active ? "有効" : "停止中", String(u.deviceCount), used, fmt(u.lastSeenAt)], buttons);
  }
  const dt = $("devices"); dt.textContent = "";
  for (const d of devices) {
    const buttons = d.revoked ? [] : [["失効", async () => {
      if (confirm(d.name + " を失効させますか。") && await api("/admin/devices/" + encodeURIComponent(d.id) + "/revoke")) load();
    }]];
    const owner = d.userId === null ? "なし" : (d.username ?? "ユーザ名未設定");
    const label = d.kind === "viewer" ? "ブラウザ (期限 " + fmt(d.expiresAt) + ")" : d.name;
    row(dt, [label + (d.revoked ? " (失効)" : ""), owner, fmt(d.createdAt), fmt(d.lastSeenAt)], buttons);
  }
  const ct = $("checkins"); ct.textContent = "";
  for (const p of checkins) {
    const where = p.venue + (p.place ? " (" + p.place + ")" : "");
    row(ct, [fmt(p.t), (p.user.username ?? "ユーザ名未設定") + (p.userStatus === "active" ? "" : " (停止中)"), where, p.category ?? "-", p.comment ?? "-"], [["削除", async () => {
      if (confirm(where + " を削除しますか。フォロワーの一覧から消えます。") && await api("/admin/checkins/" + encodeURIComponent(p.id) + "/delete")) load();
    }]]);
  }
}
// The issued code, its link (code in the fragment, never sent to the server) and the link's QR code.
function show(title, code, expiresAt, link, howto) {
  $("issued").hidden = false;
  $("for").textContent = title;
  $("code").textContent = code.match(/.{4}/g).join("-");
  $("exp").textContent = "有効期限: " + fmt(expiresAt) + "（1 回のみ使用可）";
  $("link").textContent = link;
  $("howto").textContent = howto;
  showQr($("qr"), link);
}
$("invite").onclick = async () => {
  const body = await api("/admin/invites", { ttlDays: Number($("ttl").value) });
  if (!body) return;
  show("招待", body.code, body.expiresAt, location.origin + "/pair#i=" + body.code,
    "招待する人に登録リンクを渡します。アプリの「QR コードを読み取る」でこの QR コードを読み取るか、アプリのある端末でリンクを開くと、新しく登録する画面になります。");
  load();
};
async function issuePairing(u) {
  const body = await api("/admin/users/" + encodeURIComponent(u.id) + "/pairing");
  if (!body) return;
  show(who(u) + " のペアリングコード", body.code, body.expiresAt, location.origin + "/pair#c=" + body.code,
    "この利用者の端末を追加します。アプリで QR コードを読み取るか、リンクを開くと、サーバとコードが入力されます。登録後、その端末に復旧コードを入力します。復旧コードは QR コードとリンクに含まれません。");
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
load();
</script></body></html>`);
});

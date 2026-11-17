import { Hono } from "hono";
import { parseCertFingerprints } from "./config";
import type { AppEnv } from "./env";
import { QR_SCRIPT } from "./qr";
import { base64UrlEncode, randomBytes } from "./util";

// Registration links (docs/sync-protocol.md, "Registration links"):
//   https://<server>/pair#c=<CODE>    a pairing code: add a device to a user
//   https://<server>/pair#i=<INVITE>  an invite: register a new user
// The code is in the fragment, which a browser never sends, so this route
// never sees it: the page reads it client-side, and accepts exactly these two
// fragments. With the app installed and the link verified (App Links),
// Android opens the app directly and this page is not loaded at all. Opened
// elsewhere (e.g. on a PC), the page shows the link as a QR code for the app
// to scan.

/** The app's package, the target of the App Links statement. */
export const ANDROID_PACKAGE = "net.sksat.ashidori";

export const RELEASES_URL = "https://github.com/sksat/ashidori/releases";

/** The fragments the page accepts; the same grammar as the app's PairLink. */
export const PAIR_FRAGMENT_RE = "/^#c=([0-9ABCDEFGHJKMNPQRSTVWXYZ]{8})$/";
export const INVITE_FRAGMENT_RE = "/^#i=([0-9ABCDEFGHJKMNPQRSTVWXYZ]{16})$/";

export const pair = new Hono<AppEnv>();

pair.get("/", (c) => {
  const nonce = base64UrlEncode(randomBytes(16));
  c.header(
    "Content-Security-Policy",
    `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`,
  );
  return c.html(`<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ashidori 端末の登録</title>
<style nonce="${nonce}">
body{font-family:system-ui,sans-serif;max-width:32rem;margin:2rem auto;padding:0 1rem;line-height:1.6}
#code{font:700 2.5rem ui-monospace,monospace;letter-spacing:.15em;margin:.5rem 0}
#open{display:inline-block;padding:.8rem 1.5rem;border-radius:.5rem;background:#1b5e20;color:#fff;text-decoration:none;font-weight:700}
#pairing[hidden],#invite[hidden],#ok[hidden],#bad[hidden],#manual[hidden]{display:none}#bad{color:#b00}
#qr svg{display:block;width:12rem;height:12rem;margin:.5rem 0}
.note{color:#555;font-size:.9rem}
</style></head><body>
<h1 id="title">ashidori 端末の登録</h1>
<div id="ok" hidden>
<p>登録先のサーバ: <strong id="server"></strong></p>
<div id="pairing" hidden>
<p>この端末を追加します。ペアリングコード（10 分間有効、1 回のみ使用可）</p>
<p id="code"></p>
</div>
<div id="invite" hidden>
<p>このサーバへの招待です（1 回のみ有効）</p>
</div>
<p><a id="open" href="#">アプリで開く</a></p>
<p id="note" class="note"></p>
<h2>別の端末で開いた場合</h2>
<p>アプリの「設定」→「サーバ同期」→「QR コードを読み取る」で、この QR コードを読み取ります。</p>
<div id="qr"></div>
</div>
<p id="bad" hidden>このリンクは正しくありません。発行元で登録リンクを発行し直してください。</p>
<h2>アプリがない場合</h2>
<p><a href="${RELEASES_URL}">GitHub のリリースページ</a>から APK を入手してインストールし、このページを再度開きます。<span id="manual">アプリの「設定」→「サーバ同期」で、サーバの URL とペアリングコードを入力しても登録できます。</span></p>
<script nonce="${nonce}">${QR_SCRIPT}</script>
<script nonce="${nonce}">
const $ = (id) => document.getElementById(id);
const c = ${PAIR_FRAGMENT_RE}.exec(location.hash);
const i = ${INVITE_FRAGMENT_RE}.exec(location.hash);
if (c || i) {
  const code = (c || i)[1];
  const key = c ? "c" : "i";
  $("server").textContent = location.origin;
  if (c) {
    $("code").textContent = code.slice(0, 4) + "-" + code.slice(4);
    $("pairing").hidden = false;
    $("note").textContent = "登録後、アプリで復旧コードを入力します。";
  } else {
    $("title").textContent = "ashidori 招待";
    document.title = "ashidori 招待";
    $("invite").hidden = false;
    $("manual").hidden = true;
    $("note").textContent = "アプリで登録先を確認してから登録します。";
  }
  $("open").href = "ashidori://pair?server=" + encodeURIComponent(location.origin) + "&" + key + "=" + code;
  showQr($("qr"), location.origin + "/pair#" + key + "=" + code);
  $("ok").hidden = false;
} else {
  $("bad").hidden = false;
}
</script></body></html>`);
});


/**
 * Digital Asset Links for App Links: lets the app open https://<host>/pair.
 * The fingerprints come from ANDROID_CERT_SHA256; empty (or invalid, which
 * the deploy workflow refuses) serves `[]`, and the links then open this
 * server's /pair page in the browser.
 */
export function assetLinks(env: { ANDROID_CERT_SHA256?: string }): unknown[] {
  const fingerprints = parseCertFingerprints(env.ANDROID_CERT_SHA256);
  if (fingerprints === null) {
    console.error("ANDROID_CERT_SHA256 is invalid; serving no asset links");
    return [];
  }
  if (fingerprints.length === 0) return [];
  return [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: { namespace: "android_app", package_name: ANDROID_PACKAGE, sha256_cert_fingerprints: fingerprints },
    },
  ];
}

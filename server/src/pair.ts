import { Hono } from "hono";
import { parseCertFingerprints } from "./config";
import type { AppEnv } from "./env";
import { QR_SCRIPT } from "./qr";
import { base64UrlEncode, randomBytes } from "./util";

// Registration links: https://<server>/pair#c=<CODE> (docs/sync-protocol.md,
// "Registration links"). The pairing code is in the fragment, which a browser
// never sends, so this route never sees it: the page reads it client-side.
// With the app installed and the link verified (App Links), Android opens the
// app directly and this page is not loaded at all. Opened elsewhere (e.g. on
// a PC), the page shows the link as a QR code for the app to scan.

/** The app's package, the target of the App Links statement. */
export const ANDROID_PACKAGE = "net.sksat.ashidori";

export const RELEASES_URL = "https://github.com/sksat/ashidori/releases";

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
#ok[hidden],#bad[hidden]{display:none}#bad{color:#b00}
#qr svg{display:block;width:12rem;height:12rem;margin:.5rem 0}
.note{color:#555;font-size:.9rem}
</style></head><body>
<h1>ashidori 端末の登録</h1>
<div id="ok" hidden>
<p>登録先のサーバ: <strong id="server"></strong></p>
<p>ペアリングコード（10 分間有効、1 回のみ使用可）</p>
<p id="code"></p>
<p><a id="open" href="#">アプリで開く</a></p>
<p class="note">アプリで登録先を確認してから登録します。2 台目以降の端末では、登録後に復旧コードを入力します。復旧コードはこのリンクと QR コードに含まれません。</p>
<h2>別の端末で開いた場合</h2>
<p>アプリの「設定」→「サーバ同期」→「QR コードを読み取る」で、この QR コードを読み取ります。</p>
<div id="qr"></div>
</div>
<p id="bad" hidden>このリンクは正しくありません。発行元で登録リンクを発行し直してください。</p>
<h2>アプリがない場合</h2>
<p><a href="${RELEASES_URL}">GitHub のリリースページ</a>から APK を入手してインストールし、このページをもう一度開きます。アプリの「設定」→「サーバ同期」で、サーバの URL とペアリングコードを入力しても登録できます。</p>
<script nonce="${nonce}">${QR_SCRIPT}</script>
<script nonce="${nonce}">
const m = /^#c=([0-9ABCDEFGHJKMNPQRSTVWXYZ]{8})$/.exec(location.hash);
if (m) {
  const code = m[1];
  document.getElementById("server").textContent = location.origin;
  document.getElementById("code").textContent = code.slice(0, 4) + "-" + code.slice(4);
  document.getElementById("open").href =
    "ashidori://pair?server=" + encodeURIComponent(location.origin) + "&c=" + code;
  showQr(document.getElementById("qr"), location.origin + "/pair#c=" + code);
  document.getElementById("ok").hidden = false;
} else {
  document.getElementById("bad").hidden = false;
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

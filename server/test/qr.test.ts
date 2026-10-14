import { describe, expect, it } from "vitest";
import { QR_SCRIPT } from "../src/qr";
import { QRCODE_GENERATOR_JS, QRCODE_GENERATOR_VERSION } from "../src/vendor/qrcode-generator";
import { adminCall, call } from "./helpers";

async function sha256Hex(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** The nonce of the CSP, which must allow nothing but scripts and styles with it. */
function nonceOf(res: Response, connect: string): string {
  const csp = res.headers.get("Content-Security-Policy") ?? "";
  const nonce = /script-src 'nonce-([A-Za-z0-9_-]+)'/.exec(csp)?.[1];
  expect(nonce).toBeDefined();
  expect(csp).toBe(
    `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; ${connect}frame-ancestors 'none'; base-uri 'none'; form-action 'none'`,
  );
  return nonce as string;
}

/** Every script carries the nonce; the QR encoder is one of them, inline. */
function expectQrScript(html: string, nonce: string): void {
  expect(html.match(/<script/g)?.length).toBe(html.match(new RegExp(`<script nonce="${nonce}">`, "g"))?.length);
  expect(html).not.toMatch(/<script[^>]*\bsrc=/);
  expect(html).toContain(`<script nonce="${nonce}">${QR_SCRIPT}</script>`);
}

describe("vendored QR encoder", () => {
  it("is qrcode-generator 2.0.4 dist/qrcode.js, verbatim, with its licence header", async () => {
    expect(QRCODE_GENERATOR_VERSION).toBe("2.0.4");
    expect(await sha256Hex(QRCODE_GENERATOR_JS)).toBe("79ec86f82856005b1c887905cfccfcfbec3821ca61c7fd5a952faa5f778f791c");
    expect(QRCODE_GENERATOR_JS).toContain("Copyright (c) 2009 Kazuhiko Arase");
    expect(QRCODE_GENERATOR_JS).toContain("Licensed under the MIT license");
    // Safe to inline in a <script> element.
    expect(QR_SCRIPT.toLowerCase()).not.toContain("</script");
  });
});

describe("QR codes of registration links", () => {
  it("the /pair page draws the link of the fragment's code or invite", async () => {
    const res = await call("/pair");
    const nonce = nonceOf(res, "");
    const html = await res.text();
    expectQrScript(html, nonce);
    expect(html).toContain('<div id="qr"></div>');
    expect(html).toContain('showQr($("qr"), location.origin + "/pair#" + key + "=" + code)');
    expect(html).toContain("QR コードを読み取る");
  });

  it("the admin page draws the link next to the code", async () => {
    const res = await adminCall("/admin/");
    const nonce = nonceOf(res, "connect-src 'self'; ");
    const html = await res.text();
    expectQrScript(html, nonce);
    expect(html).toMatch(/<div id="issued" hidden><div class="row"><div id="qr"><\/div>/);
    expect(html).toContain('showQr($("qr"), link)');
  });
});

import { QRCODE_GENERATOR_JS } from "./vendor/qrcode-generator";

// QR codes of registration links, drawn in the browser: the /pair page only
// knows the code from the fragment, which never reaches the server. Only
// links are encoded, never the recovery code (the server never has it).

/**
 * `showQr(el, text)`: replaces the children of `el` with an SVG of `text`
 * (error correction M, 4-module quiet zone, black on white). Built with DOM
 * calls and presentation attributes only, so the CSP needs no
 * `'unsafe-inline'` for it.
 */
const SHOW_QR_JS = `
function showQr(el, text) {
  const qr = qrcode(0, "M");
  qr.addData(text);
  qr.make();
  const n = qr.getModuleCount(), q = 4, size = String(n + 2 * q);
  let d = "";
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (qr.isDark(r, c)) d += "M" + (c + q) + " " + (r + q) + "h1v1h-1z";
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 " + size + " " + size);
  svg.setAttribute("shape-rendering", "crispEdges");
  svg.setAttribute("role", "img");
  svg.setAttribute("aria-label", "登録リンクの QR コード");
  const bg = document.createElementNS(ns, "rect");
  bg.setAttribute("width", size);
  bg.setAttribute("height", size);
  bg.setAttribute("fill", "#fff");
  const fg = document.createElementNS(ns, "path");
  fg.setAttribute("d", d);
  fg.setAttribute("fill", "#000");
  svg.append(bg, fg);
  el.replaceChildren(svg);
}
`;

/** The body of a nonce script that defines `qrcode` and `showQr`. */
export const QR_SCRIPT = QRCODE_GENERATOR_JS + SHOW_QR_JS;

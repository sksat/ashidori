// A QR code as an SVG, drawn with qrcode-generator (vite.config.ts bundles
// the copy in server/src/vendor). Error correction M and a 4-module quiet
// zone, black on white whatever the theme (readers expect it), as the
// server's /pair page draws its codes. Only presentation attributes: the CSP
// has no 'unsafe-inline' for styles.

import qrcode from "virtual:qrcode-generator";

export const QUIET_ZONE = 4;

/** The modules of `text` as one SVG path, in a square of `size` modules (quiet zone included). */
export function qrPath(text: string): { size: number; modules: number; d: string } {
  const qr = qrcode(0, "M");
  qr.addData(text, "Byte");
  qr.make();
  const n = qr.getModuleCount();
  let d = "";
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (qr.isDark(r, c)) d += `M${c + QUIET_ZONE} ${r + QUIET_ZONE}h1v1h-1z`;
  return { size: n + 2 * QUIET_ZONE, modules: n, d };
}

export function qrSvg(text: string, label: string): SVGSVGElement {
  const { size, d } = qrPath(text);
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", `0 0 ${size} ${size}`);
  svg.setAttribute("shape-rendering", "crispEdges");
  svg.setAttribute("role", "img");
  svg.setAttribute("aria-label", label);
  const bg = document.createElementNS(ns, "rect");
  bg.setAttribute("width", String(size));
  bg.setAttribute("height", String(size));
  bg.setAttribute("fill", "#fff");
  const fg = document.createElementNS(ns, "path");
  fg.setAttribute("d", d);
  fg.setAttribute("fill", "#000");
  svg.append(bg, fg);
  return svg;
}

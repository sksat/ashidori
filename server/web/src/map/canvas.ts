// Routes without a base map, on a plain canvas: what the app draws when the
// offline basemap is missing (BaseMapPolicy.Outcome.NoBasemap). Used for
// the small per-day maps and whenever MapLibre or the basemap is not
// available. Web Mercator, so it lines up with MapLibre's maps.

import type { MapContent } from "./content";
import { boundsOf, highlightBounds } from "./content";

const TILE = 256;
const MAX_ZOOM = 18;
const MIN_ZOOM = 1;

const mx = (lon: number) => (lon + 180) / 360;
const my = (lat: number) => {
  const s = Math.sin((Math.max(-85, Math.min(85, lat)) * Math.PI) / 180);
  return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
};

export interface View {
  /** centre in Mercator units [0, 1] */
  x: number;
  y: number;
  zoom: number;
}

function token(name: string, fallback: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
}

export function fitView(b: [number, number, number, number] | null, w: number, h: number, pad = 1.35, maxZoom = 16): View {
  if (!b) return { x: mx(139.7), y: my(35.68), zoom: 9 };
  const [W, S, E, N] = b;
  const x0 = mx(W);
  const x1 = mx(E);
  const y0 = my(N);
  const y1 = my(S);
  const span = Math.max((x1 - x0) * pad, 1e-9);
  const spanY = Math.max((y1 - y0) * pad, 1e-9);
  const zoom = Math.log2(Math.min(w / (span * TILE), h / (spanY * TILE)));
  return { x: (x0 + x1) / 2, y: (y0 + y1) / 2, zoom: Math.max(MIN_ZOOM, Math.min(maxZoom, zoom)) };
}

/** Draws the content on a canvas at a view. */
export function draw(canvas: HTMLCanvasElement, c: MapContent, v: View): void {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  if (!w || !h) return;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
  }
  const g = canvas.getContext("2d");
  if (!g) return;
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.fillStyle = token("--map-land", "#f3f0ea");
  g.fillRect(0, 0, w, h);
  const scale = TILE * 2 ** v.zoom;
  const px = (lon: number) => (mx(lon) - v.x) * scale + w / 2;
  const py = (lat: number) => (my(lat) - v.y) * scale + h / 2;
  const halo = token("--map-halo", "#ffffff");
  const node = token("--node", "#0b6e62");
  const primary = token("--primary", "#00695c");
  const today = token("--today", "#d9540a");
  g.lineCap = "round";
  g.lineJoin = "round";
  const line = (coords: [number, number][]) => {
    g.beginPath();
    coords.forEach(([lon, lat], i) => (i ? g.lineTo(px(lon), py(lat)) : g.moveTo(px(lon), py(lat))));
    g.stroke();
  };
  // Halos first, so that crossing routes stay readable (3:1 on both themes).
  for (const r of c.routes) {
    g.globalAlpha = r.dim ? 0.4 : 1;
    g.strokeStyle = halo;
    g.lineWidth = r.highlight ? 11 : 8;
    g.setLineDash([]);
    line(r.coords);
  }
  for (const r of c.routes) {
    g.globalAlpha = (r.dim ? 0.3 : 1) * (r.faint ? 0.45 : 1);
    g.strokeStyle = r.dashed ? today : r.color;
    g.lineWidth = r.highlight ? 7 : 4.5;
    g.setLineDash(r.dashed ? [9, 6] : []);
    line(r.coords);
  }
  g.setLineDash([]);
  g.globalAlpha = 1;
  for (const s of c.stays) {
    const x = px(s.lon);
    const y = py(s.lat);
    if (s.highlight) {
      g.beginPath();
      g.arc(x, y, 15, 0, 2 * Math.PI);
      g.strokeStyle = primary;
      g.lineWidth = 3;
      g.stroke();
    }
    g.beginPath();
    g.arc(x, y, s.highlight ? 8 : 6.5, 0, 2 * Math.PI);
    g.fillStyle = node;
    g.fill();
    g.strokeStyle = halo;
    g.lineWidth = 2.5;
    g.stroke();
  }
  for (const f of c.fixes) {
    const x = px(f.lon);
    const y = py(f.lat);
    const mPerPx = (40_075_016.7 * Math.cos((f.lat * Math.PI) / 180)) / scale;
    const r = Number.isFinite(f.acc) ? Math.max(f.acc / mPerPx, 10) : 10;
    g.beginPath();
    g.arc(x, y, r, 0, 2 * Math.PI);
    g.globalAlpha = 0.14;
    g.fillStyle = primary;
    g.fill();
    g.globalAlpha = 0.5;
    g.strokeStyle = primary;
    g.lineWidth = 1.5;
    g.stroke();
    g.globalAlpha = 1;
    g.beginPath();
    g.arc(x, y, 8, 0, 2 * Math.PI);
    g.fillStyle = f.color ?? primary;
    g.fill();
    g.strokeStyle = halo;
    g.lineWidth = 3;
    g.stroke();
  }
}

/** A still image of the content (the small per-day maps). */
export function drawStatic(canvas: HTMLCanvasElement, c: MapContent): void {
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  draw(canvas, c, fitView(highlightBounds(c) ?? boundsOf(c), w, h));
}

/** An interactive canvas map: drag, wheel, buttons and keys. */
export class CanvasMap {
  readonly canvas: HTMLCanvasElement;
  private view: View | null = null;
  private content: MapContent = { routes: [], stays: [], fixes: [] };
  private readonly ro: ResizeObserver;
  private readonly mo: MutationObserver;
  private readonly mq: MediaQueryList;
  private readonly redraw = () => this.render();
  private readonly listeners = new AbortController();

  constructor(private readonly host: HTMLElement) {
    this.canvas = document.createElement("canvas");
    this.canvas.setAttribute("aria-hidden", "true");
    this.canvas.style.width = "100%";
    this.canvas.style.height = "100%";
    host.appendChild(this.canvas);
    this.ro = new ResizeObserver(this.redraw);
    this.ro.observe(host);
    this.mo = new MutationObserver(this.redraw);
    this.mo.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    this.mq = matchMedia("(prefers-color-scheme: dark)");
    this.mq.addEventListener("change", this.redraw);
    this.bind();
  }

  private bind(): void {
    let drag: { x: number; y: number } | null = null;
    const el = this.host;
    const o = { signal: this.listeners.signal };
    el.addEventListener(
      "pointerdown",
      (e) => {
        drag = { x: e.clientX, y: e.clientY };
        el.setPointerCapture(e.pointerId);
      },
      o,
    );
    el.addEventListener("pointermove", (e) => {
      if (!drag || !this.view) return;
      const s = TILE * 2 ** this.view.zoom;
      this.view.x -= (e.clientX - drag.x) / s;
      this.view.y -= (e.clientY - drag.y) / s;
      drag = { x: e.clientX, y: e.clientY };
      this.render();
    }, o);
    const end = () => (drag = null);
    el.addEventListener("pointerup", end, o);
    el.addEventListener("pointercancel", end, o);
    el.addEventListener(
      "wheel",
      (e) => {
        e.preventDefault();
        this.zoomBy(e.deltaY < 0 ? 0.5 : -0.5);
      },
      { passive: false, signal: this.listeners.signal },
    );
    el.addEventListener("dblclick", () => this.zoomBy(1), o);
    el.addEventListener("keydown", (e) => {
      if (!this.view) return;
      const step = 0.1 * (this.host.clientWidth / (TILE * 2 ** this.view.zoom));
      const pan: Record<string, [number, number]> = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
      const m = pan[e.key];
      if (m) {
        this.view.x += m[0];
        this.view.y += m[1];
        this.render();
      } else if (e.key === "+" || e.key === "=") this.zoomBy(1);
      else if (e.key === "-" || e.key === "−") this.zoomBy(-1);
      else if (e.key === "0") this.fit();
      else return;
      e.preventDefault();
    }, o);
  }

  setContent(c: MapContent, fit = true): void {
    this.content = c;
    if (fit || !this.view) this.view = null;
    this.render();
  }

  zoomBy(d: number): void {
    if (!this.view) return;
    this.view.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, this.view.zoom + d));
    this.render();
  }

  fit(): void {
    this.view = null;
    this.render();
  }

  private render(): void {
    const w = this.host.clientWidth;
    const h = this.host.clientHeight;
    if (!w || !h) return;
    this.view ??= fitView(highlightBounds(this.content) ?? boundsOf(this.content), w, h);
    draw(this.canvas, this.content, this.view);
  }

  destroy(): void {
    this.listeners.abort();
    this.ro.disconnect();
    this.mo.disconnect();
    this.mq.removeEventListener("change", this.redraw);
    this.canvas.remove();
  }
}

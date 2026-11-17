// The map component of the mock: the map, a heading, zoom/fit buttons and
// the map style picker, the legend (bottom left) and the attribution
// (bottom right).
//
// The base map is the choice of map/styles.ts, read before anything is
// drawn: an online map (OpenFreeMap or GSI) by default, or the offline
// basemap (map/basemap.ts). The offline one is the whole archive, fetched
// once when it is chosen, verified against the pin and kept in Cache
// Storage; it is used without asking from then on. Should it be gone from
// Cache Storage, the map shows no base map and offers
// 「オフライン地図を再取得（約 64 MB）」: it never falls back to an online map.
// Until a base map is ready, and without WebGL, the routes are drawn on a
// plain canvas.

import { approxMB, basemapOffered, cachedBasemap, downloadBasemap, loadedBasemap, onBasemap } from "../map/basemap";
import { CanvasMap } from "../map/canvas";
import { EMPTY, legendModes, type MapContent } from "../map/content";
import { GlMap } from "../map/gl";
import { chooseStyle, creditsOf, currentStyle, MAP_STYLES, onStyle, planBase, type MapStyleKey } from "../map/styles";
import { modeInfo } from "../data/model";
import { h, icon, replace } from "./dom";

type Renderer = CanvasMap | GlMap;

const narrow = () => matchMedia("(max-width: 959px)").matches;

const NOTE = "オンライン地図では、表示範囲を地図の配信元に送信します。";
const NO_BASEMAP = "サーバにオフライン地図のデータがありません";
const refetchLabel = () => `オフライン地図を再取得（約 ${approxMB()} MB）`;

// ---- shared by every map on the page ----

let offeredOnce: Promise<boolean> | null = null;
/** Whether the server has the pinned offline basemap (GET /v1/info, once per page). */
const offered = () => (offeredOnce ??= basemapOffered());

type Fetching = { kind: "idle" } | { kind: "loading"; pct: number } | { kind: "failed" };
let fetching: Fetching = { kind: "idle" };
const fetchListeners = new Set<() => void>();

function setFetching(f: Fetching): void {
  fetching = f;
  for (const g of [...fetchListeners]) g();
}

/** Downloads the whole archive (map/basemap.ts); onBasemap then switches the maps. */
function fetchBasemap(): void {
  if (fetching.kind === "loading" || loadedBasemap()) return;
  setFetching({ kind: "loading", pct: 0 });
  downloadBasemap((n, total) => {
    const pct = Math.floor((n / total) * 100);
    if (fetching.kind === "loading" && pct !== fetching.pct) setFetching({ kind: "loading", pct });
  }).then(
    () => setFetching({ kind: "idle" }),
    (e: unknown) => {
      console.warn("basemap download failed", e);
      setFetching({ kind: "failed" });
    },
  );
}

/** Credit lines with links (fixed strings of this code, as text nodes). */
function attributionNodes(key: MapStyleKey): (Node | string)[] {
  const out: (Node | string)[] = [];
  for (const c of creditsOf(key)) {
    if (out.length) out.push(" ");
    out.push(h("a", { href: c.href, rel: "noreferrer", target: "_blank" }, c.text));
  }
  if (key === "offline") out.push(" · このサーバから配信");
  return out;
}

let ids = 0;

export class MapPanel {
  readonly el: HTMLElement;
  private readonly view: HTMLElement;
  private stage: HTMLElement;
  private readonly head: HTMLElement;
  private readonly legend: HTMLElement;
  private readonly attrib: HTMLElement;
  private readonly over: HTMLElement;
  private readonly pickBtn: HTMLButtonElement;
  private readonly pick: HTMLElement;
  private readonly radios = new Map<MapStyleKey, HTMLInputElement>();
  private readonly offlineNote: HTMLElement;
  private renderer: Renderer | null = null;
  /** The base map the renderer shows ("none": no base map). */
  private shown: MapStyleKey | "none" = "none";
  private content: MapContent = EMPTY;
  private destroyed = false;
  private generation = 0;
  private readonly unsubscribe: (() => void)[] = [];

  constructor(private readonly label: string) {
    const id = `mappick-${++ids}`;
    this.view = h("div", { class: "mapview", tabindex: 0, role: "group", "aria-roledescription": "地図", "aria-label": label });
    this.stage = h("div", { class: "mapview" });
    this.view.appendChild(this.stage);
    this.head = h("div", { class: "maphead" });
    this.legend = h("div");
    this.attrib = h("p", { class: "attrib", hidden: true });
    this.over = h("div", { class: "mapover", hidden: true });
    const btn = (label: string, ic: string, f: () => void) => {
      const b = h("button", { type: "button", "aria-label": label }, icon(ic));
      b.addEventListener("click", f);
      return b;
    };
    this.pickBtn = btn("地図の種類", "layers", () => this.togglePicker());
    this.pickBtn.setAttribute("aria-expanded", "false");
    this.pickBtn.setAttribute("aria-controls", id);
    this.offlineNote = h("small");
    this.pick = this.buildPicker(id);
    this.el = h(
      "section",
      { class: "mapbox", "aria-label": "地図" },
      this.view,
      this.head,
      h(
        "div",
        { class: "mapctl", role: "group", "aria-label": "地図の操作" },
        btn("拡大", "plus", () => this.renderer?.zoomBy(1)),
        btn("縮小", "minus", () => this.renderer?.zoomBy(-1)),
        btn("全体を表示", "fit", () => this.renderer?.fit()),
        this.pickBtn,
      ),
      this.pick,
      this.legend,
      this.attrib,
      this.over,
    );
    this.renderer = new CanvasMap(this.stage);
    this.bindCanvasKeys();
    this.unsubscribe.push(
      onStyle(() => void this.apply()),
      // A download from any map (or another one's button) switches this one too.
      onBasemap(() => void this.apply()),
      onFetching(() => this.renderMissing()),
    );
    void this.apply();
  }

  private buildPicker(id: string): HTMLElement {
    const name = `${id}-style`;
    const options = MAP_STYLES.map((s) => {
      const input = h("input", { type: "radio", name, value: s.key });
      input.addEventListener("change", () => {
        if (!input.checked) return;
        chooseStyle(s.key);
        // Choosing the offline map is the request to download it.
        if (s.key === "offline" && !loadedBasemap()) void offered().then((ok) => ok && currentStyle() === "offline" && fetchBasemap());
      });
      this.radios.set(s.key, input);
      return h("label", null, input, h("span", null, s.label, s.key === "offline" ? this.offlineNote : null));
    });
    const panel = h(
      "div",
      { class: "mappick", id, hidden: true },
      h("fieldset", null, h("legend", null, "地図の種類"), options),
      h("details", null, h("summary", null, "詳しく"), h("p", null, NOTE)),
    );
    panel.addEventListener("keydown", (e) => {
      if (e.key !== "Escape") return;
      this.closePicker();
      this.pickBtn.focus();
      e.stopPropagation();
    });
    return panel;
  }

  private readonly outside = (e: Event) => {
    if (!this.pick.contains(e.target as Node) && !this.pickBtn.contains(e.target as Node)) this.closePicker();
  };

  private togglePicker(): void {
    if (!this.pick.hidden) return this.closePicker();
    this.pick.hidden = false;
    this.pickBtn.setAttribute("aria-expanded", "true");
    this.syncPicker();
    document.addEventListener("pointerdown", this.outside);
    this.radios.get(currentStyle())?.focus();
  }

  private closePicker(): void {
    this.pick.hidden = true;
    this.pickBtn.setAttribute("aria-expanded", "false");
    document.removeEventListener("pointerdown", this.outside);
  }

  /** The checked style, and the offline option disabled when the server has no basemap. */
  private syncPicker(): void {
    const key = currentStyle();
    for (const [k, r] of this.radios) r.checked = k === key;
    const radio = this.radios.get("offline")!;
    const update = (ok: boolean) => {
      const here = !!loadedBasemap();
      const none = !ok && !here;
      // Kept selectable while chosen, so that the choice stays visible.
      radio.disabled = none && key !== "offline";
      this.offlineNote.textContent = none ? NO_BASEMAP : here ? "" : `約 ${approxMB()} MB`;
    };
    update(true);
    void offered().then((ok) => !this.destroyed && update(ok));
  }

  /** Keys for the canvas renderer are on the focusable view (the canvas handles them). */
  private bindCanvasKeys(): void {
    this.view.addEventListener("keydown", (e) => {
      if (this.renderer instanceof CanvasMap) this.stage.dispatchEvent(new KeyboardEvent("keydown", { key: e.key }));
      if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "+", "-", "=", "0"].includes(e.key)) e.preventDefault();
    });
  }

  /** Shows the base map of the current choice (see planBase). */
  private async apply(): Promise<void> {
    const gen = ++this.generation;
    const key = currentStyle();
    if (!this.pick.hidden) this.syncPicker();
    if (key !== "offline") {
      if (this.shown === key) return;
      const plan = planBase(key, false, false);
      if (plan.kind !== "online") return;
      await this.swap(gen, (stage) => GlMap.create(stage, { style: plan.style }), key);
      return;
    }
    // The offline map: never an online one meanwhile. Until the archive in
    // Cache Storage is verified, the routes are on a plain canvas.
    if (this.shown !== "none" && this.shown !== "offline") await this.swap(gen, async (stage) => new CanvasMap(stage), "none");
    if (this.shown === "offline" || this.destroyed || gen !== this.generation) return;
    const basemap = loadedBasemap() ?? (await cachedBasemap());
    if (this.destroyed || gen !== this.generation) return;
    const plan = planBase(key, !!basemap, false);
    if (plan.kind === "offline" && basemap) {
      await this.swap(gen, (stage) => GlMap.create(stage, basemap), "offline");
      return;
    }
    this.renderMissing();
  }

  /** The offline map is chosen but not here: 「オフライン地図を再取得」, its progress, or why not. */
  private renderMissing(): void {
    if (currentStyle() !== "offline" || this.shown !== "none" || loadedBasemap()) return;
    const gen = this.generation;
    this.attrib.hidden = false;
    if (fetching.kind === "loading") {
      replace(this.attrib, h("span", { role: "status" }, `オフライン地図を読み込み中 ${fetching.pct}%`));
      return;
    }
    const failed = fetching.kind === "failed";
    void offered().then((ok) => {
      if (this.destroyed || gen !== this.generation || fetching.kind === "loading" || loadedBasemap()) return;
      if (!ok) return replace(this.attrib, h("span", null, NO_BASEMAP));
      const b = h("button", { class: "btn tonal sm mapload", type: "button" }, icon("map"), refetchLabel());
      b.addEventListener("click", () => fetchBasemap());
      replace(this.attrib, failed ? h("span", { class: "low", role: "status" }, "読み込めませんでした ") : null, b);
    });
  }

  private async swap(gen: number, make: (stage: HTMLElement) => Promise<Renderer>, shows: MapStyleKey | "none"): Promise<void> {
    const stage = h("div", { class: "mapview" });
    // Laid out but invisible until ready: MapLibre needs the container's size.
    stage.style.visibility = "hidden";
    this.view.appendChild(stage);
    let r: Renderer;
    try {
      r = await make(stage);
    } catch (e) {
      stage.remove();
      console.warn("map renderer unavailable", e);
      if (gen === this.generation && shows !== "none" && shows !== "offline") await this.fallBack(gen);
      return;
    }
    if (this.destroyed || gen !== this.generation) {
      r.destroy();
      stage.remove();
      return;
    }
    this.renderer?.destroy();
    this.stage.remove();
    this.stage = stage;
    stage.style.visibility = "";
    this.renderer = r;
    this.shown = shows;
    if (r instanceof GlMap) {
      const canvas = stage.querySelector("canvas");
      canvas?.setAttribute("aria-label", this.label);
      if (canvas) r.bindKeys(canvas);
      this.view.setAttribute("tabindex", "-1");
    } else this.view.setAttribute("tabindex", "0");
    r.setContent(this.content, true);
    if (shows === "none") {
      replace(this.attrib);
      this.attrib.hidden = true;
    } else {
      replace(this.attrib, ...attributionNodes(shows));
      this.attrib.hidden = false;
    }
  }

  /** An online map that cannot load (no network, no WebGL): the routes on a plain canvas. */
  private async fallBack(gen: number): Promise<void> {
    if (this.shown !== "none") await this.swap(gen, async (stage) => new CanvasMap(stage), "none");
    if (this.destroyed || gen !== this.generation) return;
    this.attrib.hidden = false;
    replace(this.attrib, h("span", { class: "low" }, "地図を読み込めませんでした"));
  }

  setHead(...nodes: (Node | string)[]): void {
    replace(this.head, ...nodes);
  }

  setOverlay(...nodes: (Node | string)[]): void {
    this.over.hidden = nodes.length === 0;
    replace(this.over, nodes.length ? h("div", null, ...nodes) : null);
  }

  setContent(c: MapContent, fit = true): void {
    this.content = c;
    this.renderer?.setContent(c, fit);
    this.renderLegend();
  }

  private renderLegend(): void {
    const items: HTMLElement[] = legendModes(this.content)
      .map(modeInfo)
      .map((m) => h("li", null, h("i", { class: "sw", css: { "--c": m.color } }), m.label));
    if (this.content.routes.some((r) => r.dashed)) items.push(h("li", null, h("i", { class: "sw dash" }), "今日"));
    if (this.content.routes.some((r) => r.gap)) items.push(h("li", null, h("i", { class: "sw gap" }), "記録なし"));
    if (this.content.stays.length) items.push(h("li", null, h("i", { class: "sw dot" }), "滞在"));
    if (this.content.fixes.length) items.push(h("li", null, h("i", { class: "sw dot fix" }), "最終測位"));
    replace(this.legend, items.length ? h("details", { class: "legend", open: !narrow() }, h("summary", null, "凡例"), h("ul", null, items)) : null);
  }

  destroy(): void {
    this.destroyed = true;
    for (const f of this.unsubscribe) f();
    this.closePicker();
    this.renderer?.destroy();
    this.renderer = null;
  }
}

function onFetching(f: () => void): () => void {
  fetchListeners.add(f);
  return () => fetchListeners.delete(f);
}

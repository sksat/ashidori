// The map component of the mock: the map, a heading, zoom/fit buttons,
// 「詳しい地図」, the legend (bottom left) and the attribution (bottom
// right). It starts as a canvas without a base map and switches to
// MapLibre with the server's offline basemap once that is known to be
// there; online tiles only while 「詳しい地図」 is on.

import { offlineBasemap } from "../map/basemap";
import { CanvasMap } from "../map/canvas";
import { EMPTY, legendModes, type MapContent } from "../map/content";
import { GlMap } from "../map/gl";
import { modeInfo } from "../data/model";
import { h, icon, replace } from "./dom";

type Renderer = CanvasMap | GlMap;

const narrow = () => matchMedia("(max-width: 959px)").matches;

/** Turns the trusted attribution snippets (fixed strings in this code) into nodes without innerHTML. */
function attributionNodes(kind: "offline" | "online" | "none"): (Node | string)[] {
  if (kind === "none") return [];
  if (kind === "online") return [h("a", { href: "https://maps.gsi.go.jp/development/ichiran.html", rel: "noreferrer", target: "_blank" }, "地理院タイル")];
  return [h("a", { href: "https://www.openstreetmap.org/copyright", rel: "noreferrer", target: "_blank" }, "© OpenStreetMap contributors"), " · このサーバから配信"];
}

export class MapPanel {
  readonly el: HTMLElement;
  private readonly view: HTMLElement;
  private stage: HTMLElement;
  private readonly head: HTMLElement;
  private readonly legend: HTMLElement;
  private readonly attrib: HTMLElement;
  private readonly over: HTMLElement;
  private readonly detailBtn: HTMLButtonElement;
  private renderer: Renderer | null = null;
  private content: MapContent = EMPTY;
  private detail = false;
  private destroyed = false;
  private generation = 0;

  constructor(private readonly label: string) {
    this.view = h("div", { class: "mapview", tabindex: 0, role: "group", "aria-roledescription": "地図", "aria-label": label });
    this.stage = h("div", { class: "mapview" });
    this.view.appendChild(this.stage);
    this.head = h("div", { class: "maphead" });
    this.legend = h("div");
    this.attrib = h("p", { class: "attrib" });
    this.over = h("div", { class: "mapover", hidden: true });
    this.detailBtn = h("button", { type: "button", "aria-pressed": "false", "aria-label": "詳しい地図", "data-tip": "表示中の範囲の地図を国土地理院から取得します" }, icon("layers"));
    this.detailBtn.addEventListener("click", () => void this.setDetail(!this.detail));
    const btn = (label: string, ic: string, f: () => void) => {
      const b = h("button", { type: "button", "aria-label": label }, icon(ic));
      b.addEventListener("click", f);
      return b;
    };
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
        this.detailBtn,
      ),
      this.legend,
      this.attrib,
      this.over,
    );
    this.renderer = new CanvasMap(this.stage);
    this.bindCanvasKeys();
    void this.upgrade();
  }

  /** Keys for the canvas renderer are on the focusable view (the canvas handles them). */
  private bindCanvasKeys(): void {
    this.view.addEventListener("keydown", (e) => {
      if (this.renderer instanceof CanvasMap) this.stage.dispatchEvent(new KeyboardEvent("keydown", { key: e.key }));
      if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "+", "-", "=", "0"].includes(e.key)) e.preventDefault();
    });
  }

  /** Switches to MapLibre with the offline basemap, when the server has one. */
  private async upgrade(): Promise<void> {
    const gen = ++this.generation;
    const basemap = await offlineBasemap();
    if (!basemap || this.destroyed || gen !== this.generation || this.detail) {
      this.setAttribution("none");
      return;
    }
    await this.swap(async (stage) => GlMap.create(stage, basemap, false), "offline");
  }

  private async setDetail(on: boolean): Promise<void> {
    this.detail = on;
    this.detailBtn.setAttribute("aria-pressed", String(on));
    if (on) await this.swap(async (stage) => GlMap.create(stage, await offlineBasemap(), true), "online");
    else {
      await this.swap(async (stage) => new CanvasMap(stage), "none");
      void this.upgrade();
    }
  }

  private async swap(make: (stage: HTMLElement) => Promise<Renderer>, attribution: "offline" | "online" | "none"): Promise<void> {
    const gen = ++this.generation;
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
      if (attribution === "online") {
        this.detail = false;
        this.detailBtn.setAttribute("aria-pressed", "false");
        this.detailBtn.hidden = true;
      }
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
    if (r instanceof GlMap) {
      const canvas = stage.querySelector("canvas");
      canvas?.setAttribute("aria-label", this.label);
      if (canvas) r.bindKeys(canvas);
      this.view.setAttribute("tabindex", "-1");
    } else this.view.setAttribute("tabindex", "0");
    r.setContent(this.content, true);
    this.setAttribution(attribution);
  }

  private setAttribution(kind: "offline" | "online" | "none"): void {
    replace(this.attrib, ...attributionNodes(kind));
    this.attrib.hidden = kind === "none";
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
    if (this.content.stays.length) items.push(h("li", null, h("i", { class: "sw dot" }), "滞在"));
    if (this.content.fixes.length) items.push(h("li", null, h("i", { class: "sw dot fix" }), "最終測位"));
    replace(this.legend, items.length ? h("details", { class: "legend", open: !narrow() }, h("summary", null, "凡例"), h("ul", null, items)) : null);
  }

  destroy(): void {
    this.destroyed = true;
    this.renderer?.destroy();
    this.renderer = null;
  }
}

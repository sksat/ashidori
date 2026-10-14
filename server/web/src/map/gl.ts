// MapLibre GL JS, loaded on demand from the copy under __MAPLIBRE_PATH__
// (vite.config.ts), with the offline basemap of the server or a plain
// background. No online tiles (no request to another origin). Routes, stays
// and the last fix are GeoJSON layers.

import type * as ML from "maplibre-gl";
import type { Basemap } from "./basemap";
import type { MapContent } from "./content";
import { boundsOf, highlightBounds } from "./content";

type MapLibre = typeof ML;

let lib: Promise<MapLibre> | null = null;

/** Loads MapLibre and its stylesheet once. Rejects without WebGL. */
export function loadMapLibre(): Promise<MapLibre> {
  lib ??= (async () => {
    const probe = document.createElement("canvas");
    if (!probe.getContext("webgl2") && !probe.getContext("webgl")) throw new Error("WebGL is not available");
    if (!document.querySelector("link[data-maplibre]")) {
      const css = document.createElement("link");
      css.rel = "stylesheet";
      css.href = `${__MAPLIBRE_PATH__}maplibre-gl.css`;
      css.setAttribute("data-maplibre", "");
      document.head.appendChild(css);
    }
    const url = `${__MAPLIBRE_PATH__}maplibre-gl.mjs`;
    return (await import(/* @vite-ignore */ url)) as MapLibre;
  })();
  lib.catch(() => (lib = null));
  return lib;
}

function token(name: string, fallback: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
}

function geojson(c: MapContent, primary: string) {
  return {
    routes: {
      type: "FeatureCollection" as const,
      features: c.routes.map((r) => ({
        type: "Feature" as const,
        properties: {
          color: r.color,
          dashed: r.dashed,
          opacity: (r.dim ? 0.3 : 1) * (r.faint ? 0.45 : 1),
          halo: r.dim ? 0.4 : 1,
          width: r.highlight ? 7 : 4.5,
          haloWidth: r.highlight ? 11 : 8,
        },
        geometry: { type: "LineString" as const, coordinates: r.coords },
      })),
    },
    stays: {
      type: "FeatureCollection" as const,
      features: c.stays.map((s) => ({
        type: "Feature" as const,
        properties: { hl: s.highlight },
        geometry: { type: "Point" as const, coordinates: [s.lon, s.lat] },
      })),
    },
    fixes: {
      type: "FeatureCollection" as const,
      features: c.fixes.map((f) => ({
        type: "Feature" as const,
        properties: { acc: Number.isFinite(f.acc) ? f.acc : 0, color: f.color ?? primary },
        geometry: { type: "Point" as const, coordinates: [f.lon, f.lat] },
      })),
    },
  };
}

export class GlMap {
  private map: ML.Map | null = null;
  private content: MapContent = { routes: [], stays: [], fixes: [] };
  private ready = false;

  private constructor(
    private readonly ml: MapLibre,
    private readonly host: HTMLElement,
    /** A light base map (the offline basemap) under the routes, in both themes. */
    private readonly light: boolean,
  ) {}

  /** Colours of the light theme on a light base map, else the theme's tokens. */
  private color(name: string, light: string): string {
    return this.light ? light : token(name, light);
  }

  /** `basemap` null draws a plain background. */
  static async create(host: HTMLElement, basemap: Basemap | null): Promise<GlMap> {
    const ml = await loadMapLibre();
    ml.setWorkerUrl(`${__MAPLIBRE_PATH__}maplibre-gl-worker.mjs`);
    if (basemap) basemap.install((name, action) => ml.addProtocol(name, action));
    const g = new GlMap(ml, host, !!basemap);
    const style: ML.StyleSpecification =
      basemap?.style ?? { version: 8, sources: {}, layers: [{ id: "bg", type: "background", paint: { "background-color": token("--map-land", "#f3f0ea") } }] };
    const map = new ml.Map({
      container: host,
      style,
      attributionControl: false,
      center: [139.7, 35.68],
      zoom: 9,
      maxZoom: 17,
      dragRotate: false,
      pitchWithRotate: false,
      touchPitch: false,
      // The UI's own label: the map is not read out (the timeline is the alternative).
      cooperativeGestures: false,
    });
    map.touchZoomRotate.disableRotation();
    map.keyboard.disableRotation();
    g.map = map;
    host.querySelector("canvas")?.setAttribute("aria-hidden", "true");
    await new Promise<void>((resolve) => map.once("load", () => resolve()));
    g.addLayers();
    g.ready = true;
    g.apply(true);
    return g;
  }

  private addLayers(): void {
    const map = this.map!;
    const empty = { type: "FeatureCollection" as const, features: [] };
    for (const id of ["routes", "stays", "fixes"]) map.addSource(id, { type: "geojson", data: empty });
    const halo = this.color("--map-halo", "#ffffff");
    map.addLayer({
      id: "fix-acc",
      type: "circle",
      source: "fixes",
      paint: {
        // Accuracy in metres to pixels at the zoom (Web Mercator).
        // (512 px tiles: 78271.517 m per pixel at zoom 0, ignoring the latitude.)
        "circle-radius": ["interpolate", ["exponential", 2], ["zoom"], 0, ["/", ["get", "acc"], 78271.517], 22, ["/", ["get", "acc"], 0.018661]],
        "circle-color": ["get", "color"],
        "circle-opacity": 0.14,
        "circle-stroke-color": ["get", "color"],
        "circle-stroke-opacity": 0.5,
        "circle-stroke-width": 1.5,
      },
    });
    map.addLayer({
      id: "route-halo",
      type: "line",
      source: "routes",
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": halo, "line-width": ["get", "haloWidth"], "line-opacity": ["get", "halo"] },
    });
    map.addLayer({
      id: "route",
      type: "line",
      source: "routes",
      filter: ["!", ["get", "dashed"]],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": ["get", "color"], "line-width": ["get", "width"], "line-opacity": ["get", "opacity"] },
    });
    map.addLayer({
      id: "route-today",
      type: "line",
      source: "routes",
      filter: ["get", "dashed"],
      layout: { "line-join": "round" },
      paint: { "line-color": this.color("--today", "#d9540a"), "line-width": ["get", "width"], "line-opacity": ["get", "opacity"], "line-dasharray": [2, 1.3] },
    });
    map.addLayer({
      id: "stay",
      type: "circle",
      source: "stays",
      paint: {
        "circle-radius": ["case", ["get", "hl"], 8, 6.5],
        "circle-color": this.color("--node", "#0b6e62"),
        "circle-stroke-color": halo,
        "circle-stroke-width": 2.5,
      },
    });
    map.addLayer({
      id: "fix",
      type: "circle",
      source: "fixes",
      paint: { "circle-radius": 8, "circle-color": ["get", "color"], "circle-stroke-color": halo, "circle-stroke-width": 3 },
    });
  }

  setContent(c: MapContent, fit = true): void {
    this.content = c;
    if (this.ready) this.apply(fit);
  }

  private apply(fit: boolean): void {
    const map = this.map!;
    const d = geojson(this.content, this.color("--primary", "#00695c"));
    (map.getSource("routes") as ML.GeoJSONSource).setData(d.routes);
    (map.getSource("stays") as ML.GeoJSONSource).setData(d.stays);
    (map.getSource("fixes") as ML.GeoJSONSource).setData(d.fixes);
    if (fit) this.fit();
  }

  fit(): void {
    const b = highlightBounds(this.content) ?? boundsOf(this.content);
    if (!b || !this.map) return;
    const [w, s, e, n] = b;
    const single = w === e && s === n;
    if (single) this.map.jumpTo({ center: [w, s], zoom: 15 });
    // Clear of the heading (top left), the buttons (right) and the legend (bottom).
    else this.map.fitBounds([w, s, e, n], { padding: { top: 72, right: 88, bottom: 72, left: 56 }, maxZoom: 16, duration: 0 });
  }

  zoomBy(d: number): void {
    this.map?.zoomTo(this.map.getZoom() + d, { duration: 150 });
  }

  /** Key '0' fits (MapLibre handles arrows and +/-). */
  bindKeys(el: HTMLElement): void {
    el.addEventListener("keydown", (e) => {
      if (e.key === "0") {
        this.fit();
        e.preventDefault();
      }
    });
  }

  destroy(): void {
    this.map?.remove();
    this.map = null;
    void this.host;
    void this.ml;
  }
}

// What a map draws: routes per leg in the mode's colour, stays as points,
// and the last fix with its accuracy circle. One device at a time
// (docs/web-ui.md, "地図").

import type { DayData } from "../data/store";
import { isUncertain, modeInfo, PALETTE, type ModeKey } from "../data/model";

export interface MapRoute {
  coords: [number, number][];
  mode: ModeKey;
  color: string;
  /** Today's thinned route (not synced yet; kept for when it is). */
  dashed: boolean;
  /** Low-accuracy leg: drawn faint. */
  faint: boolean;
  /** A stretch without a record (記録なし): a dotted grey line between its ends. */
  gap: boolean;
  highlight: boolean;
  dim: boolean;
}

export interface MapStay {
  lon: number;
  lat: number;
  highlight: boolean;
}

export interface MapFix {
  lon: number;
  lat: number;
  /** metres; NaN when unknown */
  acc: number;
  /** CSS colour (recording mode), or null for the primary colour */
  color: string | null;
}

export interface MapContent {
  routes: MapRoute[];
  stays: MapStay[];
  fixes: MapFix[];
}

export const EMPTY: MapContent = { routes: [], stays: [], fixes: [] };

/** Selected row of a day: index into the day's segments. */
export interface Highlight {
  date: string;
  index: number;
}

export function contentOf(days: readonly DayData[], hl: Highlight | null = null, fixes: MapFix[] = []): MapContent {
  const routes: MapRoute[] = [];
  const stays: MapStay[] = [];
  for (const d of days) {
    const seen = new Set<string>();
    d.segments.forEach((s, i) => {
      const isHl = !!hl && hl.date === d.date && hl.index === i;
      const dim = !!hl && !isHl;
      if (s.type === "visit") {
        const k = `${s.lat.toFixed(4)},${s.lon.toFixed(4)}`;
        if (seen.has(k) && !isHl) return;
        seen.add(k);
        stays.push({ lon: s.lon, lat: s.lat, highlight: isHl });
        return;
      }
      if (s.type === "unrecorded") {
        const coords: [number, number][] = [
          [s.from.lon, s.from.lat],
          [s.to.lon, s.to.lat],
        ];
        routes.push({ coords, mode: "UNKNOWN_ACTIVITY_TYPE", color: PALETTE.unrecorded, dashed: false, faint: false, gap: true, highlight: isHl, dim });
        return;
      }
      for (const l of s.legs) {
        const coords = s.path.filter((f) => f.time >= l.start && f.time <= l.end).map((f): [number, number] => [f.lon, f.lat]);
        if (coords.length < 2) continue;
        routes.push({ coords, mode: l.mode, color: modeInfo(l.mode).color, dashed: false, faint: isUncertain(l), gap: false, highlight: isHl, dim });
      }
    });
  }
  return { routes, stays, fixes };
}

/** [west, south, east, north], or null when empty. */
export function boundsOf(c: MapContent): [number, number, number, number] | null {
  let w = Infinity;
  let s = Infinity;
  let e = -Infinity;
  let n = -Infinity;
  const add = (lon: number, lat: number) => {
    w = Math.min(w, lon);
    e = Math.max(e, lon);
    s = Math.min(s, lat);
    n = Math.max(n, lat);
  };
  for (const r of c.routes) for (const [lon, lat] of r.coords) add(lon, lat);
  for (const p of c.stays) add(p.lon, p.lat);
  for (const f of c.fixes) add(f.lon, f.lat);
  return Number.isFinite(w) ? [w, s, e, n] : null;
}

/** Bounds of the highlighted part, if any (the map zooms to it). */
export function highlightBounds(c: MapContent): [number, number, number, number] | null {
  return boundsOf({ routes: c.routes.filter((r) => r.highlight), stays: c.stays.filter((s) => s.highlight), fixes: [] });
}

/** Modes drawn, in the app's order, for the legend. */
export function legendModes(c: MapContent): ModeKey[] {
  const set = new Set(c.routes.filter((r) => !r.gap).map((r) => r.mode));
  return [...set];
}

// Records of the small streams (docs/sync-protocol.md, "Streams and
// records"). Readers ignore unknown fields and tolerate missing ones.

import type { CheckIn, DeviceStatus, ModeOverride, Place, PlaceKind } from "./model";
import { modeFromKey } from "./model";

type Obj = Record<string, unknown>;
const obj = (x: unknown): Obj | null => (typeof x === "object" && x !== null && !Array.isArray(x) ? (x as Obj) : null);
const num = (v: unknown, d = NaN) => (typeof v === "number" && Number.isFinite(v) ? v : d);
const str = (v: unknown) => (typeof v === "string" ? v : "");
const KINDS: PlaceKind[] = ["HOME", "WORK", "SCHOOL", "UNKNOWN"];

export function placeOf(deviceId: string, x: unknown): Place | null {
  const r = obj(x);
  if (!r || (typeof r.id !== "number" && typeof r.id !== "string")) return null;
  const lat = num(r.lat);
  const lon = num(r.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  const kind = KINDS.includes(r.kind as PlaceKind) ? (r.kind as PlaceKind) : "UNKNOWN";
  return { key: `${deviceId}/${r.id}`, lat, lon, radius: num(r.r, 50), name: str(r.name), kind, updatedAt: num(r.updatedAt, 0) };
}

export function checkInOf(deviceId: string, x: unknown): CheckIn | null {
  const r = obj(x);
  if (!r || (typeof r.id !== "number" && typeof r.id !== "string")) return null;
  const t = num(r.t);
  const lat = num(r.lat);
  const lon = num(r.lon);
  if (!Number.isFinite(t) || !Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  return {
    key: `${deviceId}/${r.id}`,
    time: t,
    lat,
    lon,
    venue: str(r.venue),
    category: str(r.cat),
    comment: str(r.comment),
    isPrivate: r.private === true || r.private === 1,
    updatedAt: num(r.updatedAt, 0),
  };
}

export function overrideOf(x: unknown): ModeOverride | null {
  const r = obj(x);
  if (!r) return null;
  const s = num(r.s);
  const e = num(r.e);
  if (!Number.isFinite(s) || !Number.isFinite(e) || e <= s || typeof r.mode !== "string") return null;
  return { start: s, end: e, mode: modeFromKey(r.mode), updatedAt: num(r.updatedAt, 0) };
}

/** DeviceStatus.fromJson: lenient, like the app. */
export function statusOf(x: unknown): DeviceStatus | null {
  const r = obj(x);
  if (!r || !Number.isFinite(r.t)) return null;
  const bat = obj(r.battery);
  const rec = obj(r.recording);
  const app = obj(r.app);
  const dev = obj(r.device);
  const f = obj(r.lastFix);
  const lastFix =
    f && Number.isFinite(f.t) && Number.isFinite(f.lat) && Number.isFinite(f.lon)
      ? { t: f.t as number, lat: f.lat as number, lon: f.lon as number, acc: num(f.acc) }
      : null;
  return {
    t: r.t as number,
    battery: { pct: Math.trunc(num(bat?.pct, -1)), charging: bat?.charging === true },
    recording: { on: rec?.on === true, mode: str(rec?.mode), since: num(rec?.since, 0) },
    lastFix,
    app: { versionName: str(app?.versionName), versionCode: num(app?.versionCode, 0) },
    device: { model: str(dev?.model) },
  };
}

/** The latest snapshot per key wins (by updatedAt, then by upload order). */
export function latestByKey<T extends { key: string; updatedAt: number }>(rows: Iterable<T>): Map<string, T> {
  const out = new Map<string, T>();
  for (const r of rows) {
    const prev = out.get(r.key);
    if (!prev || r.updatedAt >= prev.updatedAt) out.set(r.key, r);
  }
  return out;
}

/** Overrides have no id: the app replaces one by its (start, end). */
export function latestOverrides(rows: Iterable<ModeOverride>): ModeOverride[] {
  const m = new Map<string, ModeOverride>();
  for (const o of rows) {
    const k = `${o.start}-${o.end}`;
    const prev = m.get(k);
    if (!prev || o.updatedAt >= prev.updatedAt) m.set(k, o);
  }
  return [...m.values()];
}

/** Logging modes (status `recording.mode`) as the app labels them. */
export const RECORDING_MODES: Record<string, { label: string; token: string }> = {
  eco: { label: "省電力", token: "--rm-eco" },
  balanced: { label: "バランス", token: "--rm-bal" },
  precise: { label: "高精度", token: "--rm-pre" },
  max: { label: "最高精度", token: "--rm-max" },
};

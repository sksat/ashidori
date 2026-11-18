// Records of the small streams (docs/sync-protocol.md, "Streams and
// records"). Readers ignore unknown fields and tolerate missing ones.

import type { CheckIn, DeviceStatus, ModeOverride, Place, PlaceKind } from "./model";
import { compareOverrides, modeFromKey } from "./model";

type Obj = Record<string, unknown>;
const obj = (x: unknown): Obj | null => (typeof x === "object" && x !== null && !Array.isArray(x) ? (x as Obj) : null);
const num = (v: unknown, d = NaN) => (typeof v === "number" && Number.isFinite(v) ? v : d);
const str = (v: unknown) => (typeof v === "string" ? v : "");
const KINDS: PlaceKind[] = ["HOME", "WORK", "SCHOOL", "UNKNOWN"];
/** A tombstone (`deleted` 1, app database version 9); missing: not deleted. */
const deletedOf = (v: unknown) => v === true || v === 1;
/** The device's change counter of the row (`rev`, after app database version 9); NaN: not carried. */
const revOf = (v: unknown) => (typeof v === "number" && Number.isSafeInteger(v) && v >= 0 ? v : NaN);

/**
 * `order`: the record's place in the upload order (batches by cursor, then
 * lines), which is each device's change-counter order; it orders the
 * versions of records without `rev`.
 */
export function placeOf(deviceId: string, x: unknown, order = 0): Place | null {
  const r = obj(x);
  if (!r || (typeof r.id !== "number" && typeof r.id !== "string")) return null;
  const lat = num(r.lat);
  const lon = num(r.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  const kind = KINDS.includes(r.kind as PlaceKind) ? (r.kind as PlaceKind) : "UNKNOWN";
  return {
    key: `${deviceId}/${r.id}`,
    lat,
    lon,
    radius: num(r.r, 50),
    name: str(r.name),
    kind,
    updatedAt: num(r.updatedAt, 0),
    deleted: deletedOf(r.deleted),
    rev: revOf(r.rev),
    order,
  };
}

export function checkInOf(deviceId: string, x: unknown, order = 0): CheckIn | null {
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
    deleted: deletedOf(r.deleted),
    rev: revOf(r.rev),
    order,
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

/** A version of a row of an editable stream (key: `${deviceId}/${id}`). */
export interface Version {
  key: string;
  deleted: boolean;
  rev: number;
  order: number;
}

/**
 * `a` is a later version of its row than `b`: by the device's change
 * counter (`rev`) when both carry it; a version with `rev` is later than
 * one without (written by an app version before 9, so uploaded before);
 * else the later upload. The device clock (`updatedAt`) is never used: a
 * clock set back must not bring an older version back.
 */
export function laterVersion(a: Version, b: Version): boolean {
  const ar = Number.isFinite(a.rev);
  const br = Number.isFinite(b.rev);
  if (ar && br && a.rev !== b.rev) return a.rev > b.rev;
  if (ar !== br) return ar;
  return a.order > b.order;
}

/**
 * The rows as they are now: per key the latest version (laterVersion),
 * without the deleted rows. A tombstone is terminal: a row with a deleted
 * version anywhere is deleted, whatever came after it (the app never
 * changes a deleted row; nothing undeletes one).
 */
export function liveByKey<T extends Version>(rows: Iterable<T>): T[] {
  const latest = new Map<string, T>();
  const deleted = new Set<string>();
  for (const r of rows) {
    if (r.deleted) deleted.add(r.key);
    const prev = latest.get(r.key);
    if (!prev || laterVersion(r, prev)) latest.set(r.key, r);
  }
  return [...latest.values()].filter((r) => !deleted.has(r.key));
}

/** Overrides have no id: the app replaces one by its (start, end). The one first in compareOverrides wins. */
export function latestOverrides(rows: Iterable<ModeOverride>): ModeOverride[] {
  const m = new Map<string, ModeOverride>();
  for (const o of rows) {
    const k = `${o.start}-${o.end}`;
    const prev = m.get(k);
    if (!prev || compareOverrides(o, prev) <= 0) m.set(k, o);
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

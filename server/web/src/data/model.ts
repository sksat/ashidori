// Types shared by the data layer and the UI. They mirror the app's
// core/src/main/scala/ashidori/core/Model.scala.

/** One position fix. Unknown optional values are NaN (as in the app). */
export interface Fix {
  time: number;
  lat: number;
  lon: number;
  /** metres; NaN when unknown. A float32 value in the app. */
  accuracy: number;
  altitude: number;
  /** m/s; NaN when unknown. A float32 value in the app. */
  speed: number;
  bearing: number;
  provider: string;
}

/** Accelerometer summary over a short window (app: MotionWindow). */
export interface MotionWindow {
  start: number;
  end: number;
  /** float32 in the app */
  accelStd: number;
  accelMean: number;
  steps: number;
}

export type ModeKey =
  | "STILL"
  | "WALKING"
  | "RUNNING"
  | "CYCLING"
  | "IN_PASSENGER_VEHICLE"
  | "IN_BUS"
  | "IN_TRAIN"
  | "IN_SUBWAY"
  | "FLYING"
  | "IN_FERRY"
  | "UNKNOWN_ACTIVITY_TYPE";

export interface ModeInfo {
  key: ModeKey;
  label: string;
  emoji: string;
  /** The app's display colour (map lines, rails), the same in both themes. */
  color: string;
  vehicle: boolean;
}

/** TransportMode of the app, in its declaration order (legends use it). */
export const MODES: readonly ModeInfo[] = [
  { key: "STILL", label: "滞在", emoji: "📍", color: "#80868B", vehicle: false },
  { key: "WALKING", label: "徒歩", emoji: "🚶", color: "#2E9D57", vehicle: false },
  { key: "RUNNING", label: "ランニング", emoji: "🏃", color: "#E8710A", vehicle: false },
  { key: "CYCLING", label: "自転車", emoji: "🚲", color: "#00A3A3", vehicle: false },
  { key: "IN_PASSENGER_VEHICLE", label: "車", emoji: "🚗", color: "#5B6CD9", vehicle: true },
  { key: "IN_BUS", label: "バス", emoji: "🚌", color: "#D9822B", vehicle: true },
  { key: "IN_TRAIN", label: "電車", emoji: "🚆", color: "#1A73E8", vehicle: true },
  { key: "IN_SUBWAY", label: "地下鉄", emoji: "🚇", color: "#9334E6", vehicle: true },
  { key: "FLYING", label: "飛行機", emoji: "✈️", color: "#00838F", vehicle: true },
  { key: "IN_FERRY", label: "船", emoji: "⛴️", color: "#0277BD", vehicle: true },
  { key: "UNKNOWN_ACTIVITY_TYPE", label: "移動", emoji: "➡️", color: "#80868B", vehicle: false },
];

const BY_KEY = new Map(MODES.map((m) => [m.key, m]));

export function modeInfo(key: ModeKey): ModeInfo {
  return BY_KEY.get(key) ?? MODES[MODES.length - 1]!;
}

/** TransportMode.fromKey: Google Timeline keys and their aliases. */
export function modeFromKey(key: string): ModeKey {
  if (BY_KEY.has(key as ModeKey)) return key as ModeKey;
  switch (key) {
    case "IN_VEHICLE":
    case "IN_CAR":
    case "IN_TAXI":
    case "MOTORCYCLING":
      return "IN_PASSENGER_VEHICLE";
    case "IN_TRAM":
    case "IN_RAIL_VEHICLE":
      return "IN_TRAIN";
    case "ON_FOOT":
    case "HIKING":
      return "WALKING";
    case "ON_BICYCLE":
      return "CYCLING";
    case "SAILING":
    case "IN_BOAT":
      return "IN_FERRY";
    default:
      return "UNKNOWN_ACTIVITY_TYPE";
  }
}

export interface Visit {
  type: "visit";
  start: number;
  end: number;
  lat: number;
  lon: number;
  radius: number;
  fixCount: number;
  confidence: number;
  /** Synced place this visit matched, if any. */
  placeKey?: string;
}

export interface Leg {
  start: number;
  end: number;
  mode: ModeKey;
  distanceM: number;
  steps: number;
  confidence: number;
  maxSpeed: number;
  medianSpeed: number;
  /** Fraction of the leg's time without a good position. */
  uncertain: number;
  /** The user set a mode other than the inferred one (shown with ✎). */
  corrected?: boolean;
  /** The inferred mode, when a correction set another one. */
  inferred?: ModeKey;
}

export interface Trip {
  type: "trip";
  start: number;
  end: number;
  path: Fix[];
  legs: Leg[];
}

/**
 * A stretch without positions (記録なし) between two places: how the user
 * got from one to the other is unknown, so it is never counted as travel.
 */
export interface Unrecorded {
  type: "unrecorded";
  start: number;
  end: number;
  from: { lat: number; lon: number };
  to: { lat: number; lon: number };
}

export type Segment = Visit | Trip | Unrecorded;

/** Colours besides the modes' (core Palette): 記録なし and stays. */
export const PALETTE = { unrecorded: "#9AA0A6", stay: "#006B5B" } as const;

export const isUncertain = (l: Leg): boolean => l.uncertain >= 0.3;

export function tripDistance(t: Trip): number {
  return t.legs.reduce((a, l) => a + l.distanceM, 0);
}

/** Trip.mainMode: the mode of the longest leg. */
export function mainMode(t: Trip): ModeKey {
  if (t.legs.length === 0) return "UNKNOWN_ACTIVITY_TYPE";
  let best = t.legs[0]!;
  for (const l of t.legs) if (l.distanceM > best.distanceM) best = l;
  return best.mode;
}

// ---- Editable streams (docs/sync-protocol.md) ----

export type PlaceKind = "HOME" | "WORK" | "SCHOOL" | "UNKNOWN";

export const PLACE_KIND_LABEL: Record<PlaceKind, string> = { HOME: "自宅", WORK: "職場", SCHOOL: "学校", UNKNOWN: "" };

export interface Place {
  /** `${deviceId}/${id}`: ids are device-local. */
  key: string;
  lat: number;
  lon: number;
  radius: number;
  name: string;
  kind: PlaceKind;
  updatedAt: number;
  /** A tombstone: the place was deleted (never shown). */
  deleted: boolean;
}

/** Place.displayName without the area (the viewer has no reverse geocoder). */
export function placeName(p: Place | undefined): string {
  if (!p) return "不明な場所";
  if (p.name) return p.name;
  const k = PLACE_KIND_LABEL[p.kind];
  return k || "不明な場所";
}

export interface CheckIn {
  key: string;
  time: number;
  lat: number;
  lon: number;
  venue: string;
  category: string;
  comment: string;
  isPrivate: boolean;
  updatedAt: number;
  /** A tombstone: the check-in was deleted (never shown). */
  deleted: boolean;
}

export interface ModeOverride {
  start: number;
  end: number;
  mode: ModeKey;
  updatedAt: number;
  /** The device that made it ("" when unknown). */
  deviceId?: string;
  /** Its place in that device's upload order (the app's change counter). */
  seq?: number;
}

/**
 * The order in which corrections win, newest first: updatedAt, then the
 * device id, then the device's upload order, all descending (core
 * ModeOverride.newestFirst). Last writer wins by the devices' clocks.
 */
export function compareOverrides(a: ModeOverride, b: ModeOverride): number {
  if (a.updatedAt !== b.updatedAt) return b.updatedAt - a.updatedAt;
  const da = a.deviceId ?? "";
  const db = b.deviceId ?? "";
  if (da !== db) return da < db ? 1 : -1;
  return (b.seq ?? 0) - (a.seq ?? 0);
}

/** The latest record of the `status` stream of a device. */
export interface DeviceStatus {
  t: number;
  battery: { pct: number; charging: boolean };
  recording: { on: boolean; mode: string; since: number };
  lastFix: { t: number; lat: number; lon: number; acc: number } | null;
  app: { versionName: string; versionCode: number };
  device: { model: string };
}

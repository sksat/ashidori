// Visits and trips from fixes and motion: a port of the app's
// TimelineBuilder, StayPointDetector, ModeClassifier, Geo, Stats and
// Places (core/src/main/scala/ashidori/core), with the same thresholds.
// server/web/test/timeline.test.ts checks it against what the app's code
// builds from the same rows (fixtures written by core's WebFixtures).
//
// Differences from the app, kept small on purpose:
// - Scala's Float arithmetic is reproduced with Math.fround where the app
//   computes in float32 (Doppler speed sums, accelStd weights).
// - Ties in the per-interval smoothing are broken by the order of the
//   classes here; the app's order comes from a hash map (and may differ).
// - Unnamed places get no area name: the app's reverse geocoder index is
//   not available in the browser.
// - Day boundaries are those of the browser's time zone, not the device's.

import type { CheckIn, Fix, Leg, ModeKey, ModeOverride, MotionWindow, Place, Segment, Trip, Unrecorded, Visit } from "./model";
import { compareOverrides, modeInfo } from "./model";

// ---------------------------------------------------------------- params

export const STAY = {
  radiusM: 120.0,
  minDurationMs: 5 * 60_000,
  maxAccuracyM: 200.0,
  outlierTolerance: 2,
  outlierWindowMs: 3 * 60_000,
  mergeDistanceM: 150.0,
  mergeGapMs: 10 * 60_000,
} as const;

export const PARAMS = {
  minTripDistanceM: 150.0,
  minTripDurationMs: 2 * 60_000,
  gapMs: 90_000,
  minLegDurationMs: 2 * 60_000,
  minLegDistanceM: 250.0,
  maxSurfacingMs: 3 * 60_000,
  maxPlausibleSpeedMps: 300.0,
  unrecordedMinMs: 15 * 60_000,
} as const;

// ------------------------------------------------------------------- geo

const EARTH_RADIUS_M = 6371008.8;
const rad = (d: number) => (d * Math.PI) / 180;

export function distance(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const p1 = rad(lat1);
  const p2 = rad(lat2);
  const dp = p2 - p1;
  const dl = rad(lon2 - lon1);
  const a = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
}

const fdist = (a: { lat: number; lon: number }, b: { lat: number; lon: number }) => distance(a.lat, a.lon, b.lat, b.lon);

export function pathLength(path: readonly Fix[]): number {
  let s = 0;
  for (let i = 1; i < path.length; i++) s += fdist(path[i - 1]!, path[i]!);
  return s;
}

const hasSpeed = (f: Fix) => !Number.isNaN(f.speed);
const hasAccuracy = (f: Fix) => !Number.isNaN(f.accuracy);

class Centroid {
  private sw = 0;
  private slat = 0;
  private slon = 0;
  add(f: Fix): void {
    const acc = hasAccuracy(f) ? Math.max(3.0, f.accuracy) : 30.0;
    const w = 1.0 / (acc * acc);
    this.sw += w;
    this.slat += f.lat * w;
    this.slon += f.lon * w;
  }
  get result(): { lat: number; lon: number } {
    return { lat: this.slat / this.sw, lon: this.slon / this.sw };
  }
}

function centroid(fixes: readonly Fix[]): { lat: number; lon: number } {
  const c = new Centroid();
  for (const f of fixes) c.add(f);
  return c.result;
}

/** Geo.removeSpikes: drops single-fix excursions. */
export function removeSpikes(path: readonly Fix[], minJumpM = 80.0): Fix[] {
  if (path.length < 3) return path.slice();
  const out: Fix[] = [path[0]!];
  for (let i = 1; i < path.length - 1; i++) {
    const a = path[i - 1]!;
    const b = path[i]!;
    const c = path[i + 1]!;
    const ab = fdist(a, b);
    const bc = fdist(b, c);
    const ac = fdist(a, c);
    const spike = ab > minJumpM && bc > minJumpM && ac < Math.min(ab, bc) * 0.5;
    if (!spike) out.push(b);
  }
  out.push(path[path.length - 1]!);
  return out;
}

// --------------------------------------------------------- stay points

interface Stay {
  visit: Visit;
  from: number;
  to: number;
}

function insideStay(f: Fix, c: { lat: number; lon: number }): boolean {
  const slack = hasAccuracy(f) ? Math.min(f.accuracy, STAY.radiusM) * 0.5 : 0.0;
  return distance(f.lat, f.lon, c.lat, c.lon) <= STAY.radiusM + slack;
}

const isMoving = (f: Fix) => hasSpeed(f) && f.speed > 1.0;

function makeVisit(members: Fix[], c: { lat: number; lon: number }): Visit {
  const dists = members.map((f) => distance(f.lat, f.lon, c.lat, c.lon)).sort((a, b) => a - b);
  const r = dists[Math.min(Math.trunc(dists.length * 0.8), dists.length - 1)]!;
  const conf = Math.min(0.95, 0.5 + 0.05 * Math.min(members.length, 6) + (r < 50 ? 0.1 : 0.0));
  return {
    type: "visit",
    start: members[0]!.time,
    end: members[members.length - 1]!.time,
    lat: c.lat,
    lon: c.lon,
    radius: Math.max(r, 10.0),
    fixCount: members.length,
    confidence: conf,
  };
}

/** StayPointDetector.detect */
export function detectStays(input: readonly Fix[]): Stay[] {
  const keep = (f: Fix) => !hasAccuracy(f) || f.accuracy <= STAY.maxAccuracyM;
  const origIndex: number[] = [];
  const fixes: Fix[] = [];
  input.forEach((f, i) => {
    if (keep(f)) {
      fixes.push(f);
      origIndex.push(i);
    }
  });
  const stays: Stay[] = [];
  const n = fixes.length;
  let i = 0;
  while (i < n) {
    const members: Fix[] = [fixes[i]!];
    const acc = new Centroid();
    acc.add(fixes[i]!);
    let c = { lat: fixes[i]!.lat, lon: fixes[i]!.lon };
    let j = i + 1;
    let last = i;
    let cont = true;
    while (cont && j < n) {
      const f = fixes[j]!;
      if (insideStay(f, c)) {
        members.push(f);
        acc.add(f);
        last = j;
        c = acc.result;
        j += 1;
      } else {
        let k = j + 1;
        let back = -1;
        while (back < 0 && k < n && k - j <= STAY.outlierTolerance && fixes[k]!.time - f.time <= STAY.outlierWindowMs) {
          if (insideStay(fixes[k]!, c)) back = k;
          k += 1;
        }
        if (back >= 0) j = back;
        else cont = false;
      }
    }
    let from = i;
    let to = last;
    while (from < to && isMoving(fixes[from]!)) from += 1;
    while (to > from && isMoving(fixes[to]!)) to -= 1;
    if (fixes[to]!.time - fixes[from]!.time >= STAY.minDurationMs) {
      const t0 = fixes[from]!.time;
      const t1 = fixes[to]!.time;
      const kept = members.filter((f) => f.time >= t0 && f.time <= t1);
      stays.push({ visit: makeVisit(kept, centroid(kept)), from: origIndex[from]!, to: origIndex[to]! });
      i = last + 1;
    } else i += 1;
  }
  return mergeStays(stays, input);
}

function mergeStays(stays: Stay[], fixes: readonly Fix[]): Stay[] {
  const excursion = (prev: Stay, next: Stay) => {
    let m = 0;
    for (let k = prev.to; k <= next.from; k++) m = Math.max(m, distance(fixes[k]!.lat, fixes[k]!.lon, prev.visit.lat, prev.visit.lon));
    return m;
  };
  const acc: Stay[] = [];
  for (const s of stays) {
    const prev = acc[acc.length - 1];
    if (
      prev &&
      s.visit.start - prev.visit.end <= STAY.mergeGapMs &&
      fdist(prev.visit, s.visit) <= STAY.mergeDistanceM &&
      excursion(prev, s) <= STAY.radiusM * 2
    ) {
      const a = prev.visit;
      const b = s.visit;
      const wa = a.fixCount;
      const wb = b.fixCount;
      const merged: Visit = {
        type: "visit",
        start: a.start,
        end: b.end,
        lat: (a.lat * wa + b.lat * wb) / (wa + wb),
        lon: (a.lon * wa + b.lon * wb) / (wa + wb),
        radius: Math.max(a.radius, b.radius),
        fixCount: a.fixCount + b.fixCount,
        confidence: Math.max(a.confidence, b.confidence),
      };
      acc[acc.length - 1] = { visit: merged, from: prev.from, to: s.to };
    } else acc.push(s);
  }
  return acc;
}

// ------------------------------------------------------ mode classifier

interface LegFeatures {
  durationMs: number;
  distanceM: number;
  medianSpeed: number;
  p90Speed: number;
  maxSpeed: number;
  cadence: number;
  accelStd: number;
  stopRatio: number;
  gapRatio: number;
}

/** ModeClassifier.classify */
export function classify(f: LegFeatures): [ModeKey, number] {
  const avg = f.durationMs > 0 ? f.distanceM / (f.durationMs / 1000.0) : 0.0;
  const hasMotion = !Number.isNaN(f.cadence);
  if (f.durationMs <= 0) return ["UNKNOWN_ACTIVITY_TYPE", 0.0];
  if (f.medianSpeed > 45 || (f.p90Speed > 70 && avg > 30)) return ["FLYING", 0.85];
  if (f.gapRatio > 0.5 && (f.durationMs > 2 * 3_600_000 || f.distanceM > 80_000)) return ["UNKNOWN_ACTIVITY_TYPE", 0.2];
  if (f.gapRatio > 0.5 && avg >= 4 && avg <= 35) return ["IN_SUBWAY", 0.55];
  if (f.medianSpeed < 0.4 && f.stopRatio > 0.8 && (!hasMotion || f.cadence < 10)) return ["STILL", 0.6];
  if (hasMotion && f.cadence >= 30 && f.medianSpeed < 3.5)
    return f.cadence >= 135 && f.medianSpeed >= 2.2 ? ["RUNNING", 0.75] : ["WALKING", 0.85];
  if (f.medianSpeed < 1.9 && f.p90Speed < 3.0) return ["WALKING", hasMotion ? 0.6 : 0.7];
  if (f.medianSpeed < 8.5 && f.p90Speed < 12.5 && looksLikeCycling(f, hasMotion)) return ["CYCLING", 0.6];
  if (f.medianSpeed < 3.0) return ["WALKING", 0.4];
  if (f.p90Speed >= 31) return ["IN_TRAIN", 0.65];
  if (!Number.isNaN(f.accelStd) && f.accelStd < 0.25 && f.medianSpeed > 8) return ["IN_TRAIN", 0.5];
  if (f.stopRatio > 0.3 && f.p90Speed < 15) return ["IN_BUS", 0.35];
  return ["IN_PASSENGER_VEHICLE", 0.5];
}

function looksLikeCycling(f: LegFeatures, hasMotion: boolean): boolean {
  if (hasMotion) return f.cadence < 30 && (Number.isNaN(f.accelStd) || f.accelStd >= 0.8);
  return f.stopRatio < 0.3;
}

const Coarse = { Still: 0, Foot: 1, Mid: 2, Vehicle: 3, Gap: 4 } as const;
type Coarse = (typeof Coarse)[keyof typeof Coarse];

function coarse(speed: number, cadence: number, gap: boolean): Coarse {
  if (gap) return Coarse.Gap;
  if (speed < 0.5 && (Number.isNaN(cadence) || cadence < 10)) return Coarse.Still;
  if (!Number.isNaN(cadence) && cadence >= 30 && speed < 3.5) return Coarse.Foot;
  if (speed < 2.0) return Coarse.Foot;
  if (speed < 7.0) return Coarse.Mid;
  return Coarse.Vehicle;
}

// --------------------------------------------------------- motion index

export class MotionIndex {
  private readonly sorted: MotionWindow[];
  constructor(windows: readonly MotionWindow[]) {
    this.sorted = windows.slice().sort((a, b) => a.start - b.start);
  }

  summary(from: number, to: number): { steps: number; cadence: number; accelStd: number } {
    const s = this.sorted;
    if (s.length === 0 || to <= from) return { steps: 0, cadence: NaN, accelStd: NaN };
    let lo = 0;
    let hi = s.length;
    while (lo < hi) {
      const mid = (lo + hi) >>> 1;
      if (s[mid]!.end <= from) lo = mid + 1;
      else hi = mid;
    }
    let covered = 0;
    let steps = 0.0;
    let stdSum = 0.0;
    for (let i = lo; i < s.length && s[i]!.start < to; i++) {
      const w = s[i]!;
      const ov = Math.min(to, w.end) - Math.max(from, w.start);
      if (ov > 0) {
        const frac = ov / Math.max(1, w.end - w.start);
        covered += ov;
        steps += w.steps * frac;
        // Float * Long in the app: a float32 product.
        stdSum += Math.fround(Math.fround(ov) * w.accelStd);
      }
    }
    if (covered < (to - from) * 0.3) return { steps: Math.round(steps), cadence: NaN, accelStd: NaN };
    return { steps: Math.round(steps), cadence: (steps * 60000.0) / covered, accelStd: stdSum / covered };
  }
}

// ------------------------------------------------------ timeline builder

/** TimelineBuilder.build */
export function buildTimeline(rawFixes: readonly Fix[], motion: readonly MotionWindow[] = []): Segment[] {
  if (rawFixes.length === 0) return [];
  const sorted = rawFixes.slice().sort((a, b) => a.time - b.time);
  const fixes = removeSpikes(sorted);
  const index = new MotionIndex(motion);
  return splitAtJumps(fixes, PARAMS.maxPlausibleSpeedMps).flatMap((run) => buildRun(run, index));
}

export function splitAtJumps(fixes: readonly Fix[], maxSpeed: number): Fix[][] {
  if (fixes.length === 0) return [];
  const cuts: number[] = [];
  for (let i = 1; i < fixes.length; i++) {
    const a = fixes[i - 1]!;
    const b = fixes[i]!;
    const d = fdist(a, b);
    if (d > 1_000 && d / Math.max(1.0, (b.time - a.time) / 1000.0) > maxSpeed) cuts.push(i);
  }
  const bounds = [0, ...cuts, fixes.length];
  const out: Fix[][] = [];
  for (let k = 0; k + 1 < bounds.length; k++) out.push(fixes.slice(bounds[k], bounds[k + 1]));
  return out;
}

function buildRun(fixes: Fix[], motion: MotionIndex): Segment[] {
  const stays = detectStays(fixes);
  const out: Segment[] = [];
  const addTrip = (from: number, to: number, betweenStays: boolean) => {
    if (to <= from) return;
    const path = fixes.slice(from, to + 1);
    const dist = Math.max(fdist(path[0]!, path[path.length - 1]!), pathLength(path) * 0.5);
    const dur = path[path.length - 1]!.time - path[0]!.time;
    const minDist = betweenStays ? PARAMS.minTripDistanceM / 3 : PARAMS.minTripDistanceM;
    if (dist >= minDist || (dur >= PARAMS.minTripDurationMs && dist >= minDist / 2)) out.push(...splitUnrecorded(path, pieces(path, motion)));
  };
  let cursor = 0;
  stays.forEach((s, k) => {
    addTrip(cursor, s.from, k > 0);
    out.push(s.visit);
    cursor = s.to;
  });
  addTrip(cursor, fixes.length - 1, false);
  return absorbEdgeStops(out);
}

/** TimelineBuilder.splitUnrecorded: stretches without a record between the trips around them. */
function splitUnrecorded(path: Fix[], ps: [Leg, boolean][]): Segment[] {
  if (!ps.some(([, u]) => u)) return [{ type: "trip", start: path[0]!.time, end: path[path.length - 1]!.time, path, legs: ps.map(([l]) => l) }];
  const at = (t: number) => {
    const f = path.find((x) => x.time >= t) ?? path[path.length - 1]!;
    return { lat: f.lat, lon: f.lon };
  };
  const out: Segment[] = [];
  let ls: Leg[] = [];
  const flush = () => {
    if (ls.length === 0) return;
    const a = ls[0]!.start;
    const b = ls[ls.length - 1]!.end;
    out.push({ type: "trip", start: a, end: b, path: path.filter((f) => f.time >= a && f.time <= b), legs: ls });
    ls = [];
  };
  for (const [l, unrecorded] of ps) {
    if (unrecorded) {
      flush();
      out.push({ type: "unrecorded", start: l.start, end: l.end, from: at(l.start), to: at(l.end) } satisfies Unrecorded);
    } else ls.push(l);
  }
  flush();
  return out;
}

function absorbEdgeStops(segs: Segment[]): Segment[] {
  const a = segs.slice();
  for (let i = 0; i < a.length; i++) {
    const s = a[i]!;
    if (s.type !== "trip" || s.legs.length <= 1) continue;
    let trip: Trip = s;
    const prev = a[i - 1];
    if (i > 0 && trip.legs[0]!.mode === "STILL" && prev?.type === "visit") {
      const cut = trip.legs[0]!.end;
      a[i - 1] = { ...prev, end: cut };
      trip = { ...trip, start: cut, legs: trip.legs.slice(1), path: trip.path.filter((f) => f.time >= cut) };
    }
    const next = a[i + 1];
    if (i + 1 < a.length && trip.legs.length > 1 && trip.legs[trip.legs.length - 1]!.mode === "STILL" && next?.type === "visit") {
      const cut = trip.legs[trip.legs.length - 1]!.start;
      a[i + 1] = { ...next, start: cut };
      trip = { ...trip, end: cut, legs: trip.legs.slice(0, -1), path: trip.path.filter((f) => f.time <= cut) };
    }
    a[i] = trip;
  }
  return a;
}

interface Interval {
  start: number;
  end: number;
  dist: number;
  speed: number;
  gap: boolean;
  silent: boolean;
  poor: boolean;
  cadence: number;
  accelStd: number;
  coarse: Coarse;
}

function intervals(path: Fix[], motion: MotionIndex): Interval[] {
  const out: Interval[] = [];
  for (let k = 0; k < path.length - 1; k++) {
    const a = path[k]!;
    const b = path[k + 1]!;
    const dt = Math.max(1, b.time - a.time);
    const d = fdist(a, b);
    // A long silence where the place changed is a gap too, at any speed.
    const silent = dt >= PARAMS.unrecordedMinMs && d >= STAY.radiusM;
    const gap = (dt > PARAMS.gapMs && d / (dt / 1000.0) > 2.5) || silent;
    const doppler = !gap && hasSpeed(a) && hasSpeed(b) && dt < 30_000;
    // Float + Float in the app, then / 2.0 as a double.
    const speed = doppler ? Math.fround(a.speed + b.speed) / 2.0 : d / (dt / 1000.0);
    const dist = doppler ? Math.min(d, (speed * dt) / 1000.0) : d;
    const m = motion.summary(a.time, b.time);
    const poor = (hasAccuracy(a) && a.accuracy > 50) || (hasAccuracy(b) && b.accuracy > 50);
    out.push({ start: a.time, end: b.time, dist, speed, gap, silent, poor, cadence: m.cadence, accelStd: m.accelStd, coarse: coarse(speed, m.cadence, gap) });
  }
  return out;
}

type Run = [number, number, Coarse];

/** TimelineBuilder.legs: splits a trip path into legs of one mode. */
export function legs(path: Fix[], motion: MotionIndex): Leg[] {
  return pieces(path, motion).map(([l]) => l);
}

/** The legs, each with whether it is a stretch without a record. */
function pieces(path: Fix[], motion: MotionIndex): [Leg, boolean][] {
  if (path.length < 2) return [];
  const iv = intervals(path, motion);
  const smoothed = iv.map((x, i) => {
    if (x.coarse === Coarse.Gap) return Coarse.Gap;
    const lo = Math.max(0, i - 2);
    const hi = Math.min(iv.length - 1, i + 2);
    const w = new Map<Coarse, number>();
    for (let j = lo; j <= hi; j++) {
      const y = iv[j]!;
      if (y.coarse !== Coarse.Gap) w.set(y.coarse, (w.get(y.coarse) ?? 0) + (y.end - y.start));
    }
    let best: Coarse = x.coarse;
    let bw = -1;
    for (const [c, v] of w) if (v > bw) [best, bw] = [c, v];
    return best;
  });
  let runs: Run[] = [];
  let s = 0;
  for (let i = 1; i <= iv.length; i++) {
    if (i === iv.length || smoothed[i] !== smoothed[s]) {
      runs.push([s, i - 1, smoothed[s]!]);
      s = i;
    }
  }
  const runDur = (r: Run) => iv[r[1]]!.end - iv[r[0]]!.start;
  const runDist = (r: Run) => {
    let d = 0;
    for (let k = r[0]; k <= r[1]; k++) d += iv[k]!.dist;
    return d;
  };
  const short = (r: Run) => {
    switch (r[2]) {
      case Coarse.Gap:
        return false;
      case Coarse.Still:
        return runDur(r) < STAY.minDurationMs;
      default:
        return runDur(r) < PARAMS.minLegDurationMs && runDist(r) < PARAMS.minLegDistanceM;
    }
  };
  let changed = true;
  while (changed && runs.length > 1) {
    changed = false;
    let idx = -1;
    let idxDur = Infinity;
    runs.forEach((r, k) => {
      if (short(r) && runDur(r) < idxDur) {
        idx = k;
        idxDur = runDur(r);
      }
    });
    if (idx >= 0) {
      const k = idx;
      const r = runs[k]!;
      const moving = (j: number) => runs[j]![2] !== Coarse.Still && runs[j]![2] !== Coarse.Gap;
      const target =
        k === 0
          ? 1
          : k === runs.length - 1
            ? k - 1
            : moving(k - 1) !== moving(k + 1)
              ? moving(k - 1)
                ? k - 1
                : k + 1
              : runDur(runs[k - 1]!) >= runDur(runs[k + 1]!)
                ? k - 1
                : k + 1;
      const t = runs[target]!;
      runs[target] = [Math.min(r[0], t[0]), Math.max(r[1], t[1]), t[2]];
      runs.splice(k, 1);
      changed = true;
    }
    const merged: Run[] = [];
    for (const r of runs) {
      const lastRun = merged[merged.length - 1];
      if (lastRun && lastRun[2] === r[2]) merged[merged.length - 1] = [lastRun[0], r[1], r[2]];
      else merged.push(r);
    }
    runs = merged;
  }
  // A brief vehicle-speed run between two GNSS outages is the same
  // underground ride surfacing, not a car ride between two subway rides.
  // Walking stays: that is a transfer.
  let k = 1;
  while (k < runs.length - 1) {
    const r = runs[k]!;
    const prev = runs[k - 1]!;
    const next = runs[k + 1]!;
    const vehicle = r[2] === Coarse.Vehicle || r[2] === Coarse.Mid;
    if (vehicle && prev[2] === Coarse.Gap && next[2] === Coarse.Gap && runDur(r) < PARAMS.maxSurfacingMs) {
      runs[k - 1] = [prev[0], next[1], Coarse.Gap];
      runs.splice(k, 2);
    } else k++;
  }
  return runs.map(([a, b]) => makeLeg(iv.slice(a, b + 1), motion));
}

function weightedQuantile(sorted: [number, number][], q: number): number {
  const total = sorted.reduce((a, x) => a + x[1], 0);
  let acc = 0;
  for (const [v, w] of sorted) {
    acc += w;
    if (acc >= total * q) return v;
  }
  return sorted[sorted.length - 1]![0];
}

function makeLeg(iv: Interval[], motion: MotionIndex): [Leg, boolean] {
  const start = iv[0]!.start;
  const end = iv[iv.length - 1]!.end;
  const dur = end - start;
  const dist = iv.reduce((a, x) => a + x.dist, 0);
  const moving = iv.filter((x) => !x.gap);
  let med: number;
  let p90: number;
  let mx: number;
  if (moving.length === 0) {
    const v = dist / Math.max(1.0, dur / 1000.0);
    med = p90 = mx = v;
  } else {
    const sorted = moving.map((x): [number, number] => [x.speed, x.end - x.start]).sort((a, b) => a[0] - b[0]);
    med = weightedQuantile(sorted, 0.5);
    p90 = weightedQuantile(sorted, 0.9);
    mx = sorted[sorted.length - 1]![0];
  }
  const stopTime = moving.filter((x) => x.speed < 1.0).reduce((a, x) => a + (x.end - x.start), 0);
  const gapTime = iv.filter((x) => x.gap).reduce((a, x) => a + (x.end - x.start), 0);
  const m = motion.summary(start, end);
  const [mode, conf] = classify({
    durationMs: dur,
    distanceM: dist,
    medianSpeed: med,
    p90Speed: p90,
    maxSpeed: mx,
    cadence: m.cadence,
    accelStd: m.accelStd,
    stopRatio: dur > 0 ? stopTime / dur : 0.0,
    gapRatio: dur > 0 ? gapTime / dur : 0.0,
  });
  const rough = iv.filter((x) => x.gap || x.poor).reduce((a, x) => a + (x.end - x.start), 0);
  const silent = iv.filter((x) => x.silent).reduce((a, x) => a + (x.end - x.start), 0);
  return [
    { start, end, mode, distanceM: dist, steps: m.steps, confidence: conf, maxSpeed: mx, medianSpeed: med, uncertain: dur > 0 ? rough / dur : 0.0 },
    isUnrecorded(mode, silent, dur, walked(m.steps, m.cadence)),
  ];
}

/** TimelineBuilder.walked: enough steps at a walking rate; motion rows alone are not travel. */
export function walked(steps: number, cadence: number): boolean {
  return !Number.isNaN(cadence) && steps >= 30 && cadence >= 10.0;
}

/**
 * TimelineBuilder.isUnrecorded: mostly a long silence, unless there is strong
 * evidence of how the user moved: steps (on foot) or a flight's speed. The
 * speed between the ends and the mere presence of motion rows are not.
 */
export function isUnrecorded(mode: ModeKey, silentMs: number, durationMs: number, walkedMeanwhile: boolean): boolean {
  if (silentMs <= 0 || silentMs * 2 < durationMs) return false;
  switch (mode) {
    case "FLYING":
      return false;
    case "WALKING":
    case "RUNNING":
      return !walkedMeanwhile;
    default:
      return true;
  }
}

// ------------------------------------------------- corrections, places

/**
 * TimelineBuilder.applyOverrides: a correction applies to a leg if they
 * overlap by more than half, the newest first. A correction to the inferred
 * mode itself (the app's 取り消し) leaves the leg as built.
 */
export function applyOverrides(segs: Segment[], overrides: readonly ModeOverride[]): Segment[] {
  if (overrides.length === 0) return segs;
  const newest = overrides.slice().sort(compareOverrides);
  return segs.map((s) => {
    if (s.type !== "trip") return s;
    return {
      ...s,
      legs: s.legs.map((l) => {
        const o = newest.find((o) => Math.min(o.end, l.end) - Math.max(o.start, l.start) > Math.trunc((l.end - l.start) / 2));
        return !o || o.mode === l.mode ? l : { ...l, mode: o.mode, confidence: 1.0, corrected: true, inferred: l.mode };
      }),
    };
  });
}

/** Stats.shown: a day with nothing but stretches without a record is a day without a record. */
export function shownSegments(segs: Segment[]): Segment[] {
  return segs.every((s) => s.type === "unrecorded") ? [] : segs;
}

/** Places.matchRadius / nearest */
export function nearestPlace(v: Visit, places: readonly Place[]): Place | undefined {
  let best: Place | undefined;
  let bestD = Infinity;
  for (const p of places) {
    const d = distance(p.lat, p.lon, v.lat, v.lon);
    const r = Math.max(p.radius, 80.0) + Math.min(v.radius, 100.0) * 0.5;
    if (d <= r && d < bestD) {
      best = p;
      bestD = d;
    }
  }
  return best;
}

export function linkPlaces(segs: Segment[], places: readonly Place[]): Segment[] {
  return segs.map((s) => {
    if (s.type !== "visit") return s;
    const p = nearestPlace(s, places);
    return p ? { ...s, placeKey: p.key } : s;
  });
}

/** Check-ins made during a visit and near it. */
export function checkInsOf(v: Visit, checkIns: readonly CheckIn[]): CheckIn[] {
  return checkIns.filter(
    (c) => c.time >= v.start - 10 * 60_000 && c.time <= v.end + 10 * 60_000 && distance(c.lat, c.lon, v.lat, v.lon) <= Math.max(v.radius, 100) + 150,
  );
}

// -------------------------------------------------------- days and stats

/** "YYYY-MM-DD" of a time in the browser's time zone. */
export function dayKey(t: number): string {
  const d = new Date(t);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** Start of a day in the browser's time zone. */
export function dayStart(key: string): number {
  const [y, m, d] = key.split("-").map(Number);
  return new Date(y!, m! - 1, d!).getTime();
}

export function addDays(key: string, n: number): string {
  const [y, m, d] = key.split("-").map(Number);
  const t = new Date(Date.UTC(y!, m! - 1, d! + n));
  return t.toISOString().slice(0, 10);
}

function clip(s: Segment, from: number, to: number): Segment {
  const a = Math.max(s.start, from);
  const b = Math.min(s.end, to);
  if (a === s.start && b === s.end) return s;
  if (s.type === "visit" || s.type === "unrecorded") return { ...s, start: a, end: b };
  return {
    ...s,
    start: a,
    end: b,
    path: s.path.filter((f) => f.time >= a && f.time <= b),
    legs: s.legs
      .filter((l) => l.end > a && l.start < b)
      .map((l) => {
        const la = Math.max(l.start, a);
        const lb = Math.min(l.end, b);
        const lf = (lb - la) / Math.max(1, l.end - l.start);
        return { ...l, start: la, end: lb, distanceM: l.distanceM * lf, steps: Math.trunc(l.steps * lf) };
      }),
  };
}

/** Stats.splitByDay: segments per calendar day, clipped at midnight. */
export function splitByDay(segs: readonly Segment[]): Map<string, Segment[]> {
  const out = new Map<string, Segment[]>();
  for (const s of segs) {
    let d = dayKey(s.start);
    const last = dayKey(Math.max(s.start, s.end - 1));
    for (;;) {
      const ds = dayStart(d);
      const de = dayStart(addDays(d, 1));
      const list = out.get(d) ?? [];
      list.push(clip(s, ds, de));
      out.set(d, list);
      if (d >= last) break;
      d = addDays(d, 1);
    }
  }
  return out;
}

export interface DaySummary {
  date: string;
  distanceByMode: Map<ModeKey, number>;
  durationByMode: Map<ModeKey, number>;
  steps: number;
  visits: number;
  /** Distinct synced places among the visits (unmatched visits each count once). */
  places: number;
  /** Trips only: stretches without a record are never travel. */
  totalDistanceM: number;
  /** Time without a record (not in the totals). */
  unrecordedMs: number;
}

/** Stats.day */
export function summarizeDay(date: string, segs: readonly Segment[], steps: number): DaySummary {
  const dist = new Map<ModeKey, number>();
  const dur = new Map<ModeKey, number>();
  let visits = 0;
  const places = new Set<string>();
  let unrecorded = 0;
  for (const s of segs) {
    if (s.type === "unrecorded") {
      unrecorded += s.end - s.start;
      continue;
    }
    if (s.type === "visit") {
      visits++;
      places.add(s.placeKey ?? `${s.lat.toFixed(3)},${s.lon.toFixed(3)}`);
      continue;
    }
    for (const l of s.legs) {
      if (l.mode === "STILL") continue;
      dist.set(l.mode, (dist.get(l.mode) ?? 0) + l.distanceM);
      dur.set(l.mode, (dur.get(l.mode) ?? 0) + (l.end - l.start));
    }
  }
  let total = 0;
  for (const v of dist.values()) total += v;
  return { date, distanceByMode: dist, durationByMode: dur, steps, visits, places: places.size, totalDistanceM: total, unrecordedMs: unrecorded };
}

/** Distance per mode of several days, longest first. */
export function modeTotals(days: readonly DaySummary[]): [ModeKey, number][] {
  const m = new Map<ModeKey, number>();
  for (const d of days) for (const [k, v] of d.distanceByMode) m.set(k, (m.get(k) ?? 0) + v);
  return [...m.entries()].sort((a, b) => b[1] - a[1]);
}

export const modeColor = (k: ModeKey) => modeInfo(k).color;

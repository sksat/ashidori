import { describe, expect, it } from "vitest";
import { fixesOf, motionOf, readParquet } from "../src/data/archive";
import type { Fix, ModeKey, Segment } from "../src/data/model";
import { applyOverrides, buildTimeline, classify, dayKey, removeSpikes, splitByDay, summarizeDay } from "../src/data/timeline";
import { fixture, fixtureJson } from "./helpers";

interface ExpVisit {
  type: "visit";
  start: number;
  end: number;
  lat: number;
  lon: number;
  radius: number;
  fixCount: number;
}
interface ExpTrip {
  type: "trip";
  start: number;
  end: number;
  pathSize: number;
  legs: { start: number; end: number; mode: ModeKey; distanceM: number; steps: number; uncertain: number; confidence: number }[];
}
interface Expected {
  date: string;
  overrides: { s: number; e: number; mode: ModeKey }[];
  raw: (ExpVisit | ExpTrip)[];
  segments: (ExpVisit | ExpTrip)[];
  summary: { distanceByMode: Record<string, number>; durationByMode: Record<string, number>; steps: number; visits: number };
}

const close = (a: number, b: number) => Math.abs(a - b) <= 1e-6 * Math.max(1, Math.abs(b));

function expectSame(got: Segment[], want: (ExpVisit | ExpTrip)[]) {
  expect(got.map((s) => s.type)).toEqual(want.map((s) => s.type));
  got.forEach((g, i) => {
    const w = want[i]!;
    expect([g.start, g.end], `segment ${i}`).toEqual([w.start, w.end]);
    if (g.type === "visit" && w.type === "visit") {
      expect(g.fixCount).toBe(w.fixCount);
      expect(close(g.lat, w.lat) && close(g.lon, w.lon) && close(g.radius, w.radius), `visit ${i}`).toBe(true);
    } else if (g.type === "trip" && w.type === "trip") {
      expect(g.path.length).toBe(w.pathSize);
      expect(g.legs.map((l) => [l.start, l.end, l.mode, l.steps])).toEqual(w.legs.map((l) => [l.start, l.end, l.mode, l.steps]));
      g.legs.forEach((l, j) => {
        const e = w.legs[j]!;
        expect(close(l.distanceM, e.distanceM), `leg ${i}.${j} distance ${l.distanceM} vs ${e.distanceM}`).toBe(true);
        expect(close(l.uncertain, e.uncertain)).toBe(true);
        expect(l.confidence).toBe(e.confidence);
      });
    }
  });
}

describe("timeline, against the app's TimelineBuilder on the same rows", async () => {
  const fixes = fixesOf(await readParquet(fixture("commute-fixes.parquet")));
  const motion = motionOf(await readParquet(fixture("commute-motion.parquet")));
  const exp = fixtureJson<Expected>("commute-timeline.json");

  it("builds the same visits, trips and legs", () => {
    expectSame(buildTimeline(fixes, motion), exp.raw);
  });

  it("applies overrides, splits by day and summarises as the app does", () => {
    const overrides = exp.overrides.map((o) => ({ start: o.s, end: o.e, mode: o.mode, updatedAt: 0 }));
    const segs = splitByDay(applyOverrides(buildTimeline(fixes, motion), overrides)).get(exp.date) ?? [];
    expectSame(segs, exp.segments);
    const ds = new Date(2026, 9, 5).getTime();
    const steps = motion.filter((m) => m.start >= ds && m.start < ds + 86_400_000).reduce((a, m) => a + m.steps, 0);
    const sum = summarizeDay(exp.date, segs, steps);
    expect(sum.steps).toBe(exp.summary.steps);
    expect(sum.visits).toBe(exp.summary.visits);
    expect([...sum.distanceByMode.keys()].sort()).toEqual(Object.keys(exp.summary.distanceByMode).sort());
    for (const [k, v] of sum.distanceByMode) expect(close(v, exp.summary.distanceByMode[k]!)).toBe(true);
    for (const [k, v] of sum.durationByMode) expect(v).toBe(exp.summary.durationByMode[k]);
  });

  it("uses the browser's time zone for days (Tokyo in tests)", () => {
    expect(dayKey(exp.raw[0]!.start)).toBe("2026-10-05");
  });
});

describe("timeline rules", () => {
  const at = (t: number, dn: number, de: number, x: Partial<Fix> = {}): Fix => ({
    time: t,
    lat: 35 + dn / 111_320,
    lon: 139 + de / 91_000,
    accuracy: 10,
    altitude: NaN,
    speed: NaN,
    bearing: NaN,
    provider: "gps",
    ...x,
  });

  it("drops single spikes but keeps fast straight motion", () => {
    const walk = [at(0, 0, 0), at(10, 10, 0), at(20, 300, 300), at(30, 20, 0), at(40, 30, 0)];
    expect(removeSpikes(walk)).toHaveLength(4);
    const train = Array.from({ length: 5 }, (_, i) => at(i * 5, i * 150, 0));
    expect(removeSpikes(train)).toHaveLength(5);
  });

  it("classifies like ModeClassifier", () => {
    const base = { durationMs: 600_000, distanceM: 1000, medianSpeed: 1.4, p90Speed: 1.8, maxSpeed: 2, cadence: 100, accelStd: 1.5, stopRatio: 0, gapRatio: 0 };
    expect(classify(base)[0]).toBe("WALKING");
    expect(classify({ ...base, cadence: 150, medianSpeed: 2.8 })[0]).toBe("RUNNING");
    expect(classify({ ...base, medianSpeed: 50 })[0]).toBe("FLYING");
    expect(classify({ ...base, gapRatio: 0.8, distanceM: 6000 })[0]).toBe("IN_SUBWAY");
    expect(classify({ ...base, cadence: NaN, medianSpeed: 15, p90Speed: 33 })[0]).toBe("IN_TRAIN");
    expect(classify({ ...base, cadence: NaN, medianSpeed: 6, p90Speed: 13, stopRatio: 0.4 })[0]).toBe("IN_BUS");
  });

  it("makes one visit of a long stay with sparse fixes", () => {
    const fixes = Array.from({ length: 12 }, (_, i) => at(i * 300_000, (i % 3) * 5, 0));
    const segs = buildTimeline(fixes);
    expect(segs.map((s) => s.type)).toEqual(["visit"]);
  });

  it("clips a visit across midnight into both days", () => {
    const ds = new Date(2026, 9, 5).getTime();
    const fixes = Array.from({ length: 13 }, (_, i) => at(ds - 3_600_000 + i * 600_000, 0, 0));
    const days = splitByDay(buildTimeline(fixes));
    expect([...days.keys()]).toEqual(["2026-10-04", "2026-10-05"]);
    expect(days.get("2026-10-05")![0]!.start).toBe(ds);
  });
});

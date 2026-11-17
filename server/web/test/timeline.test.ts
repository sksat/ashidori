import { describe, expect, it } from "vitest";
import { fixesOf, motionOf, readParquet } from "../src/data/archive";
import { MODES, PALETTE, type Fix, type ModeKey, type MotionWindow, type Segment } from "../src/data/model";
import { applyOverrides, buildTimeline, classify, dayKey, isUnrecorded, removeSpikes, shownSegments, splitByDay, summarizeDay, walked } from "../src/data/timeline";
import { latestOverrides } from "../src/data/records";
import { contentOf, legendModes } from "../src/map/content";
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
    const segs = splitByDay(buildTimeline(fixes, motion, overrides)).get(exp.date) ?? [];
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

describe("stretches without a record (記録なし), against the app on the same rows", () => {
  type ExpUnrecorded = { type: "unrecorded"; start: number; end: number; from: { lat: number; lon: number }; to: { lat: number; lon: number } };
  interface GapExp {
    date: string;
    fixes: [number, number, number, number][];
    motion: [number, number, number, number, number][];
    segments: (ExpVisit | ExpTrip | ExpUnrecorded)[];
    summary: { totalDistanceM: number; movingMs: number; unrecordedMs: number };
  }
  const exp = fixtureJson<GapExp>("gaps-timeline.json");
  const fixes: Fix[] = exp.fixes.map(([time, lat, lon, accuracy]) => ({ time, lat, lon, accuracy, altitude: NaN, speed: NaN, bearing: NaN, provider: "gps" }));
  const motion = exp.motion.map(([start, end, accelStd, accelMean, steps]) => ({ start, end, accelStd, accelMean, steps }));
  const segs = buildTimeline(fixes, motion);
  const day = splitByDay(segs).get(exp.date) ?? [];

  it("builds the same visits, trips and unrecorded stretches", () => {
    expect(segs.map((s) => s.type)).toEqual(exp.segments.map((s) => s.type));
    segs.forEach((g, i) => {
      const w = exp.segments[i]!;
      expect([g.start, g.end], `segment ${i}`).toEqual([w.start, w.end]);
      if (g.type === "unrecorded" && w.type === "unrecorded") expect(close(g.from.lat, w.from.lat) && close(g.to.lon, w.to.lon)).toBe(true);
      if (g.type === "trip" && w.type === "trip") expect(g.legs.map((l) => l.mode)).toEqual(w.legs.map((l) => l.mode));
    });
    // The phone off for 3 hours, motion rows without steps, the phone off at a subway's speed, the phone still in a car.
    expect(segs.filter((s) => s.type === "unrecorded")).toHaveLength(4);
  });

  it("leaves them out of the day's distance and travel time", () => {
    const sum = summarizeDay(exp.date, day, 0);
    expect(close(sum.totalDistanceM, exp.summary.totalDistanceM)).toBe(true);
    expect([...sum.durationByMode.values()].reduce((a, b) => a + b, 0)).toBe(exp.summary.movingMs);
    expect(sum.unrecordedMs).toBe(exp.summary.unrecordedMs);
    expect(sum.distanceByMode.has("UNKNOWN_ACTIVITY_TYPE")).toBe(false);
  });

  it("keeps a trip when the mode has evidence of its own", () => {
    const h = 3_600_000;
    expect(isUnrecorded("UNKNOWN_ACTIVITY_TYPE", 3 * h, 3 * h, true)).toBe(true);
    expect(isUnrecorded("WALKING", h, h, false)).toBe(true);
    expect(isUnrecorded("WALKING", h, h, true)).toBe(false);
    expect(isUnrecorded("IN_SUBWAY", h, h, false)).toBe(true);
    expect(isUnrecorded("IN_TRAIN", h, h, true)).toBe(true);
    expect(isUnrecorded("FLYING", 3 * h, 3 * h, false)).toBe(false);
    expect(isUnrecorded("IN_BUS", h / 4, h, false)).toBe(false);
    expect(walked(0, 0)).toBe(false);
    expect(walked(500, NaN)).toBe(false);
    expect(walked(1200, 90)).toBe(true);
  });

  it("converges after 取り消し: the newer correction to the inferred mode wins and is not marked", () => {
    const leg = segs.flatMap((s) => (s.type === "trip" ? s.legs : [])).find((l) => l.mode === "IN_SUBWAY")!;
    // Both rows synced: the change to train, then the app's 取り消し (the inferred mode, newer).
    const rows = [
      { start: leg.start, end: leg.end, mode: "IN_TRAIN" as const, updatedAt: 1 },
      { start: leg.start, end: leg.end, mode: "IN_SUBWAY" as const, updatedAt: 2 },
    ];
    for (const os of [latestOverrides(rows), rows, rows.slice().reverse()]) {
      const got = applyOverrides(segs, os).flatMap((s) => (s.type === "trip" ? s.legs : [])).find((l) => l.start === leg.start)!;
      expect(got).toEqual(leg);
      expect(got.corrected).toBeUndefined();
    }
    // Before the 取り消し arrived: the correction, marked.
    const changed = applyOverrides(segs, [rows[0]!]).flatMap((s) => (s.type === "trip" ? s.legs : [])).find((l) => l.start === leg.start)!;
    expect([changed.mode, changed.corrected, changed.inferred]).toEqual(["IN_TRAIN", true, "IN_SUBWAY"]);
  });

  it("picks the same correction as the app for equal timestamps (updatedAt, device, upload order)", () => {
    interface OrderExp {
      rows: { s: number; e: number; mode: ModeKey; updatedAt: number; device: string; seq: number }[];
      expected: { start: number; mode: ModeKey }[];
    }
    const o = fixtureJson<OrderExp>("override-order.json");
    const rows = o.rows.map((r) => ({ start: r.s, end: r.e, mode: r.mode, updatedAt: r.updatedAt, deviceId: r.device, seq: r.seq }));
    const legsOf = (ss: Segment[]) => ss.flatMap((s) => (s.type === "trip" ? s.legs : []));
    for (const order of [rows, rows.slice().reverse()]) {
      const got = legsOf(applyOverrides(segs, order));
      for (const e of o.expected) expect(got.find((l) => l.start === e.start)?.mode).toBe(e.mode);
    }
    // latestOverrides keeps the same row per leg.
    const kept = legsOf(applyOverrides(segs, latestOverrides(rows.slice().reverse())));
    for (const e of o.expected) expect(kept.find((l) => l.start === e.start)?.mode).toBe(e.mode);
  });

  it("shows a day with nothing but stretches without a record as a day without a record, as the app does", () => {
    const u = segs.find((s) => s.type === "unrecorded")!;
    expect(shownSegments([u])).toEqual([]);
    expect(shownSegments(day)).toEqual(day);
  });

  it("draws them as a dotted grey line, not as a mode", () => {
    const c = contentOf([{ date: exp.date, segments: day } as never]);
    const gaps = c.routes.filter((r) => r.gap);
    expect(gaps).toHaveLength(4);
    expect(gaps[0]!.color).toBe(PALETTE.unrecorded);
    expect(legendModes(c)).not.toContain("UNKNOWN_ACTIVITY_TYPE");
  });
});

describe("adjacent legs of one mode merged, against the app on the same rows", () => {
  type ExpLeg = ExpTrip["legs"][number] & { inferred: ModeKey | null };
  interface MergeExp {
    date: string;
    fixes: [number, number, number, number][];
    motion: [number, number, number, number, number][];
    cases: {
      name: string;
      overrides: { s: number; e: number; mode: ModeKey; updatedAt: number }[];
      segments: (ExpVisit | (Omit<ExpTrip, "legs"> & { legs: ExpLeg[] }))[];
      durationByMode: Record<string, number>;
    }[];
  }
  const exp = fixtureJson<MergeExp>("merge-timeline.json");
  const fixes: Fix[] = exp.fixes.map(([time, lat, lon, accuracy]) => ({ time, lat, lon, accuracy, altitude: NaN, speed: NaN, bearing: NaN, provider: "gps" }));
  const motion = exp.motion.map(([start, end, accelStd, accelMean, steps]) => ({ start, end, accelStd, accelMean, steps }));

  for (const c of exp.cases) {
    it(`builds the same legs (${c.name})`, () => {
      const overrides = c.overrides.map((o) => ({ start: o.s, end: o.e, mode: o.mode, updatedAt: o.updatedAt }));
      const segs = buildTimeline(fixes, motion, overrides);
      expectSame(segs, c.segments);
      const legsOf = (ss: typeof segs) => ss.flatMap((s) => (s.type === "trip" ? s.legs : []));
      const wantLegs = c.segments.flatMap((s) => (s.type === "trip" ? s.legs : []));
      expect(legsOf(segs).map((l) => [l.inferred ?? null, l.corrected === true])).toEqual(wantLegs.map((l) => [l.inferred, l.inferred !== null]));
      const sum = summarizeDay(exp.date, splitByDay(segs).get(exp.date) ?? [], 0);
      expect(Object.fromEntries(sum.durationByMode)).toEqual(c.durationByMode);
    });
  }

  it("merges a long drive with many class switches fast", () => {
    // 2,000 runs of a minute each, alternating 5 m/s and 12 m/s, a car's vibration: about 33 h of 車.
    const fs: Fix[] = [];
    const ms: MotionWindow[] = [];
    let north = 0;
    let t = 1_800_000_000_000;
    for (let k = 0; k < 2000; k++)
      for (let i = 0; i < 12; i++) {
        fs.push({ time: t, lat: 36 + north / 111_320, lon: 140, accuracy: 8, altitude: NaN, speed: NaN, bearing: NaN, provider: "gps" });
        ms.push({ start: t, end: t + 5_000, accelStd: Math.fround(0.4), accelMean: Math.fround(0.1), steps: 0 });
        t += 5_000;
        north += (k % 2 === 0 ? 5 : 12) * 5;
      }
    const started = performance.now();
    const segs = buildTimeline(fs, ms);
    const took = performance.now() - started;
    expect(segs.flatMap((s) => (s.type === "trip" ? s.legs.map((l) => l.mode) : []))).toEqual(["IN_PASSENGER_VEHICLE"]);
    expect(took, `${took} ms`).toBeLessThan(3_000);
  });

  it("merges a slow start, a drive around a short outage and a corrected walk into their neighbours", () => {
    const vehicleLegs = (name: string) =>
      exp.cases.find((c) => c.name === name)!.segments.flatMap((s) => (s.type === "trip" ? [s.legs.filter((l) => l.mode !== "STILL").map((l) => l.mode)] : []));
    const car = "IN_PASSENGER_VEHICLE";
    // 13:00: as one leg 地下鉄, so cut in halves; the first two join again at the cut.
    expect(vehicleLegs("none")).toEqual([[car], [car], ["WALKING", car], [car], [car, car]]);
    expect(vehicleLegs("onePart")[3]).toEqual(["IN_BUS", "IN_PASSENGER_VEHICLE"]);
    expect(vehicleLegs("bothParts")[3]).toEqual(["IN_BUS"]);
    // Changed to 電車 for the whole leg, then 取り消し (the app writes back the mode shown before): バス again.
    expect(vehicleLegs("bothPartsChanged")[3]).toEqual(["IN_TRAIN"]);
    const undone = exp.cases.find((c) => c.name === "bothPartsChangedUndone")!;
    expect(undone.overrides[undone.overrides.length - 1]!.mode).toBe("IN_BUS");
    expect(undone.segments).toEqual(exp.cases.find((c) => c.name === "bothParts")!.segments);
    expect(vehicleLegs("joinNeighbour")[2]).toEqual(["IN_PASSENGER_VEHICLE"]);
  });
});

describe("one palette", () => {
  interface PaletteExp {
    modes: { key: ModeKey; label: string; emoji: string; color: string; vehicle: boolean }[];
    unrecorded: string;
    stay: string;
  }
  it("has the app's mode labels and colours, in its order", () => {
    const p = fixtureJson<PaletteExp>("palette.json");
    expect(MODES.map((m) => ({ ...m }))).toEqual(p.modes);
    expect({ ...PALETTE }).toEqual({ unrecorded: p.unrecorded, stay: p.stay });
  });
});

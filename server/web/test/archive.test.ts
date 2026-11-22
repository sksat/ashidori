import { parquetMetadata } from "hyparquet";
import { describe, expect, it } from "vitest";
import { ArchiveAssembler, fileInfo, fixesOf, motionOf, parseArchiveRecord, readParquet, type Cell } from "../src/data/archive";
import { fromHex } from "../src/data/bytes";
import { importBatchKey, openBlob } from "../src/data/crypto";
import { blobOf, fixture, fixtureJson, listing, syncMeta } from "./helpers";

interface Rows {
  columns: { name: string; type: string }[];
  rows: (number | string | null)[][];
}

/** A cell as the fixture writes it: Int64 as a decimal string, NaN as null. */
function norm(v: Cell | undefined, type: string): number | string | null {
  if (v === null || v === undefined) return null;
  if (type === "Int64") return typeof v === "bigint" ? v.toString() : String(v);
  if (typeof v === "number" && Number.isNaN(v)) return null;
  return v as number | string;
}

async function expectSameRows(pq: string, json: string) {
  const bytes = fixture(pq);
  const expected = fixtureJson<Rows>(json);
  const t = await readParquet(bytes);
  expect(t.columns).toEqual(expected.columns.map((c) => c.name));
  expect(t.rows).toHaveLength(expected.rows.length);
  t.rows.forEach((r, i) => {
    const e = expected.rows[i]!;
    expected.columns.forEach((c, j) => {
      const got = norm(r[c.name], c.type);
      const want = e[j]!;
      // Float32 values must be exact. (The fixture's JSON writer drops the
      // sign of -0.0; it is checked separately below.)
      if (!Object.is(got, want) && !(want === 0 && got === 0)) expect.fail(`${pq} row ${i} ${c.name}: ${String(got)} != ${String(want)}`);
    });
  });
}

describe("Parquet written by the app's ParquetWriter", () => {
  it("uses the encodings the reader must support", () => {
    const meta = parquetMetadata(fixture("commute-fixes.parquet").buffer as ArrayBuffer);
    const enc = new Set(meta.row_groups.flatMap((g) => g.columns.flatMap((c) => c.meta_data?.encodings ?? [])));
    expect(enc).toContain("DELTA_BINARY_PACKED");
    expect(enc).toContain("BYTE_STREAM_SPLIT");
    expect(meta.row_groups[0]!.columns[0]!.meta_data?.codec).toBe("GZIP");
  });

  it("reads the fixes table exactly", () => expectSameRows("commute-fixes.parquet", "commute-fixes.rows.json"));
  it("reads the motion table across row groups", () => expectSameRows("commute-motion.parquet", "commute-motion.rows.json"));
  it("reads edge cases: int64/int32 extremes, wrapping deltas, NaN as null, -0.0, UTF-8, an all-null column", () =>
    expectSameRows("edge.parquet", "edge.rows.json"));

  it("keeps the sign of -0.0", async () => {
    const t = await readParquet(fixture("edge.parquet"));
    expect(Object.is(t.rows[3]!.f32, -0)).toBe(true);
    expect(Object.is(t.rows[4]!.f64, -0)).toBe(true);
  });

  it("maps rows to fixes and motion windows as Archive.fixOf/motionOf do", async () => {
    const fixes = fixesOf(await readParquet(fixture("commute-fixes.parquet")));
    const motion = motionOf(await readParquet(fixture("commute-motion.parquet")));
    expect(fixes.length).toBeGreaterThan(500);
    expect(fixes.every((f, i) => i === 0 || f.time >= fixes[i - 1]!.time)).toBe(true);
    expect(typeof fixes[0]!.time).toBe("number");
    expect(fixes.some((f) => Number.isNaN(f.bearing))).toBe(true);
    expect(motion.every((m) => m.end > m.start && Number.isInteger(m.steps))).toBe(true);
  });
});

describe("archive parts", () => {
  it("validates records", () => {
    const ok = { file: "fixes/2026-10-05.parquet", gen: "2b7e1", part: 0, parts: 2, data: "AAEC" };
    expect(parseArchiveRecord(ok)?.data).toEqual(new Uint8Array([0, 1, 2]));
    expect(parseArchiveRecord({ ...ok, part: 2 })).toBeNull();
    expect(parseArchiveRecord({ ...ok, parts: 0 })).toBeNull();
    expect(parseArchiveRecord({ ...ok, file: "../x.parquet" })).toBeNull();
    expect(parseArchiveRecord({ ...ok, gen: "xyz" })).toBeNull();
    expect(parseArchiveRecord({ ...ok, data: "@@" })).toBeNull();
    expect(fileInfo("motion/2026-10.parquet")).toEqual({ table: "motion", period: "2026-10", month: true });
  });

  it("reassembles the complete generation from the app's sealed parts and ignores an abandoned one", async () => {
    const key = await importBatchKey(fromHex(syncMeta().batchKeyHex));
    const asm = new ArchiveAssembler();
    const rows = listing().items.filter((r) => r.stream === "archive");
    let completed: Uint8Array | null = null;
    // Newest first, as the viewer opens them.
    for (const r of rows.slice().reverse()) {
      const [rec] = await openBlob(key, r, blobOf(r.deviceId, "archive", r.seq));
      const part = parseArchiveRecord(rec)!;
      const done = asm.add(part, r.seq);
      if (done && part.file.startsWith("fixes/")) completed = done;
    }
    expect(completed).toEqual(fixture("commute-fixes.parquet"));
    const files = asm.complete();
    // Tables the viewer does not use (state spans, pressure, PICS) assemble as well.
    expect([...files.keys()].sort()).toEqual([
      "energy_state/2026-10-05.parquet", "fixes/2026-10-05.parquet", "motion/2026-10-05.parquet", "pics_adv/2026-10-05.parquet",
      "pressure/2026-10-05.parquet"]);
    expect(files.get("fixes/2026-10-05.parquet")).toEqual(fixture("commute-fixes.parquet"));
    expect(files.get("motion/2026-10-05.parquet")).toEqual(fixture("commute-motion.parquet"));
    expect(asm.incomplete()).toEqual([]);
  });

  it("does not use a generation with a missing part", () => {
    const asm = new ArchiveAssembler();
    const p = (part: number, gen = "a") => ({ file: "fixes/2026-10-05.parquet", gen, part, parts: 3, data: new Uint8Array([part]) });
    expect(asm.add(p(0), 1)).toBeNull();
    expect(asm.add(p(2), 3)).toBeNull();
    expect(asm.complete().size).toBe(0);
    expect(asm.incomplete()).toEqual(["fixes/2026-10-05.parquet"]);
    // A part that disagrees on the count is dropped.
    expect(asm.add({ ...p(1), parts: 4 }, 2)).toBeNull();
    expect(asm.add(p(1), 2)).toEqual(new Uint8Array([0, 1, 2]));
  });
});

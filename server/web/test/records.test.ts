import { describe, expect, it } from "vitest";
import { checkInOf, liveByKey, placeOf } from "../src/data/records";

// Records of the editable streams as the app uploads them (docs/sync-protocol.md):
// a deletion is a tombstone, `deleted` 1 on the row's latest snapshot.
const checkIn = (id: number, updatedAt: number, extra: Record<string, unknown> = {}) => ({
  id, t: 1000 + id, lat: 35.6, lon: 139.7, venue: `v${id}`, cat: "", ext: "", place: 1, comment: "", private: 0, updatedAt, ...extra,
});
const place = (id: number, updatedAt: number, extra: Record<string, unknown> = {}) => ({
  id, lat: 35.6, lon: 139.7, r: 50, name: `p${id}`, kind: "UNKNOWN", ext: "", updatedAt, ...extra,
});

describe("tombstones of check-ins and places", () => {
  it("reads deleted: 1 or true is a tombstone; missing (older app versions) or 0 is not", () => {
    expect(checkInOf("d", checkIn(1, 10, { deleted: 1 }))?.deleted).toBe(true);
    expect(checkInOf("d", checkIn(1, 10, { deleted: true }))?.deleted).toBe(true);
    expect(checkInOf("d", checkIn(1, 10, { deleted: 0 }))?.deleted).toBe(false);
    expect(checkInOf("d", checkIn(1, 10))?.deleted).toBe(false);
    expect(placeOf("d", place(1, 10, { deleted: 1 }))?.deleted).toBe(true);
    expect(placeOf("d", place(1, 10))?.deleted).toBe(false);
  });

  it("a tombstone wins over the earlier snapshots of its id and takes the row out", () => {
    const rows = [checkIn(1, 10), checkIn(2, 10), checkIn(1, 20, { deleted: 1 })].map((r) => checkInOf("d", r)!);
    expect(liveByKey(rows).map((c) => c.venue)).toEqual(["v2"]);
    const places = [place(1, 10), place(1, 30, { deleted: 1 }), place(2, 10)].map((r) => placeOf("d", r)!);
    expect(liveByKey(places).map((p) => p.name)).toEqual(["p2"]);
  });

  it("ids are per device: another device's row of the same id stays", () => {
    const rows = [checkInOf("a", checkIn(1, 10))!, checkInOf("b", checkIn(1, 10))!, checkInOf("a", checkIn(1, 20, { deleted: 1 }))!];
    expect(liveByKey(rows).map((c) => c.key)).toEqual(["b/1"]);
  });

  it("an older snapshot uploaded after the tombstone does not bring the row back", () => {
    const rows = [checkIn(1, 20, { deleted: 1 }), checkIn(1, 10)].map((r) => checkInOf("d", r)!);
    expect(liveByKey(rows)).toEqual([]);
  });
});

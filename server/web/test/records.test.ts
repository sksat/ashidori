import { describe, expect, it } from "vitest";
import { checkInOf, liveByKey, placeOf } from "../src/data/records";

// Records of the editable streams as the app uploads them (docs/sync-protocol.md):
// a deletion is a tombstone, `deleted` 1 on the row's latest snapshot; `rev` is
// the device's change counter, which orders the versions of a row.
const checkIn = (id: number, updatedAt: number, extra: Record<string, unknown> = {}) => ({
  id, t: 1000 + id, lat: 35.6, lon: 139.7, venue: `v${id}`, cat: "", ext: "", place: 1, comment: "", private: 0, updatedAt, ...extra,
});
const place = (id: number, updatedAt: number, extra: Record<string, unknown> = {}) => ({
  id, lat: 35.6, lon: 139.7, r: 50, name: `p${id}`, kind: "UNKNOWN", ext: "", updatedAt, ...extra,
});
/** The records in upload order, as the store reads them. */
const checkIns = (device: string, rows: Record<string, unknown>[]) => rows.map((r, i) => checkInOf(device, r, i)!);
const places = (device: string, rows: Record<string, unknown>[]) => rows.map((r, i) => placeOf(device, r, i)!);

describe("tombstones of check-ins and places", () => {
  it("reads deleted: 1 or true is a tombstone; missing (older app versions) or 0 is not", () => {
    expect(checkInOf("d", checkIn(1, 10, { deleted: 1 }))?.deleted).toBe(true);
    expect(checkInOf("d", checkIn(1, 10, { deleted: true }))?.deleted).toBe(true);
    expect(checkInOf("d", checkIn(1, 10, { deleted: 0 }))?.deleted).toBe(false);
    expect(checkInOf("d", checkIn(1, 10))?.deleted).toBe(false);
    expect(placeOf("d", place(1, 10, { deleted: 1 }))?.deleted).toBe(true);
    expect(placeOf("d", place(1, 10))?.deleted).toBe(false);
  });

  it("a tombstone takes the row out", () => {
    const rows = checkIns("d", [checkIn(1, 10, { rev: 1 }), checkIn(2, 10, { rev: 2 }), checkIn(1, 20, { rev: 3, deleted: 1 })]);
    expect(liveByKey(rows).map((c) => c.venue)).toEqual(["v2"]);
    const ps = places("d", [place(1, 10, { rev: 1 }), place(1, 30, { rev: 3, deleted: 1 }), place(2, 10, { rev: 2 })]);
    expect(liveByKey(ps).map((p) => p.name)).toEqual(["p2"]);
  });

  it("ids are per device: another device's row of the same id stays", () => {
    const rows = [checkInOf("a", checkIn(1, 10, { rev: 1 }), 0)!, checkInOf("b", checkIn(1, 10, { rev: 1 }), 1)!,
      checkInOf("a", checkIn(1, 20, { rev: 2, deleted: 1 }), 2)!];
    expect(liveByKey(rows).map((c) => c.key)).toEqual(["b/1"]);
  });

  it("a tombstone is terminal: a version uploaded after it does not bring the row back", () => {
    expect(liveByKey(checkIns("d", [checkIn(1, 20, { rev: 2, deleted: 1 }), checkIn(1, 10, { rev: 1 })]))).toEqual([]);
    expect(liveByKey(checkIns("d", [checkIn(1, 20, { rev: 2, deleted: 1 }), checkIn(1, 30, { rev: 3 })]))).toEqual([]);
  });
});

describe("the latest version does not depend on the device clock", () => {
  it("a deletion at 100 after a live version at 200 (the clock set back) deletes", () => {
    const rows = checkIns("d", [checkIn(1, 200, { rev: 5 }), checkIn(1, 100, { rev: 6, deleted: 1 })]);
    expect(liveByKey(rows)).toEqual([]);
  });

  it("replace and 取り消し with the clock going back: the change counter decides", () => {
    // Replaced at 300 (rev 7), then the clock set back: 取り消し writes the old values at 100 (rev 8).
    const rows = checkIns("d", [
      checkIn(1, 200, { rev: 6, venue: "スターバックス" }),
      checkIn(1, 300, { rev: 7, venue: "ドトール" }),
      checkIn(1, 100, { rev: 8, venue: "スターバックス" }),
    ]);
    expect(liveByKey(rows).map((c) => c.venue)).toEqual(["スターバックス"]);
    // Uploaded out of order (another batch first): still rev.
    const shuffled = checkIns("d", [
      checkIn(1, 100, { rev: 8, venue: "スターバックス" }),
      checkIn(1, 300, { rev: 7, venue: "ドトール" }),
    ]);
    expect(liveByKey(shuffled).map((c) => c.venue)).toEqual(["スターバックス"]);
  });

  it("a place renamed with the clock set back keeps the later name", () => {
    const ps = places("d", [place(1, 500, { rev: 3, name: "古い名前" }), place(1, 50, { rev: 4, name: "新しい名前" })]);
    expect(liveByKey(ps).map((p) => p.name)).toEqual(["新しい名前"]);
  });

  it("versions without rev (earlier app versions): the later upload; one with rev is later than any without", () => {
    expect(liveByKey(checkIns("d", [checkIn(1, 300, { venue: "a" }), checkIn(1, 100, { venue: "b" })])).map((c) => c.venue)).toEqual(["b"]);
    expect(liveByKey(checkIns("d", [checkIn(1, 300, { rev: 1, venue: "a" }), checkIn(1, 900, { venue: "b" })])).map((c) => c.venue)).toEqual(["a"]);
  });
});

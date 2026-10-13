import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { HOUR_MS } from "../src/util";
import {
  adminCall,
  authed,
  call,
  CHECKIN,
  feed,
  follow,
  legacyDevice,
  newDevice,
  newUser,
  putCheckin,
  unshare,
} from "./helpers";

async function storedRow(localId: string): Promise<Record<string, unknown>> {
  return (await env.DB.prepare("SELECT * FROM shared_checkins WHERE local_id = ?1")
    .bind(localId)
    .first<Record<string, unknown>>())!;
}

describe("PUT /v1/checkins/shared/:localId", () => {
  it("shares, updates in place and lists with the user", async () => {
    const alice = await newUser("alice", "alice");
    const res = await putCheckin(alice.token, "c1", { ...CHECKIN, category: "cafe", place: "Tokyo", comment: "good\ncoffee" });
    expect(res.status).toBe(200);
    const { id, cursor } = (await res.json()) as { id: string; cursor: number };
    expect((await storedRow("c1")).visibility).toBe("followers");

    const { items, next } = await feed(alice.token);
    expect(next).toBeNull();
    expect(items).toEqual([
      {
        id,
        cursor,
        user: { id: alice.userId, handle: "alice", name: "alice" },
        t: CHECKIN.t,
        lat: CHECKIN.lat,
        lon: CHECKIN.lon,
        venue: "Cafe",
        category: "cafe",
        place: "Tokyo",
        comment: "good\ncoffee",
        updatedAt: expect.any(Number),
        deleted: false,
      },
    ]);
    expect(items[0]!.updatedAt % HOUR_MS).toBe(0);

    // Same local id: same server id, new cursor, optional fields cleared.
    const updated = (await (await putCheckin(alice.token, "c1", { ...CHECKIN, venue: " Bar " })).json()) as {
      id: string;
      cursor: number;
    };
    expect(updated.id).toBe(id);
    expect(updated.cursor).toBeGreaterThan(cursor);
    const after = await feed(alice.token, `?after=${cursor}`);
    expect(after.items).toEqual([
      expect.objectContaining({ id, venue: "Bar", category: null, place: null, comment: null }),
    ]);
    expect((await feed(alice.token)).items).toHaveLength(1);
  });

  it("does not move the cursor for an unchanged re-upload", async () => {
    const a = await newDevice("a");
    const first = (await (await putCheckin(a.token, "c1", CHECKIN)).json()) as { cursor: number };
    const again = (await (await putCheckin(a.token, "c1", CHECKIN)).json()) as { cursor: number };
    expect(again.cursor).toBe(first.cursor);
  });

  it("keeps local ids per device", async () => {
    const alice = await newUser("alice");
    const a2 = await newDevice("a2", alice.userId);
    await putCheckin(alice.token, "c1", CHECKIN);
    await putCheckin(a2.token, "c1", { ...CHECKIN, venue: "Other" });
    expect((await feed(alice.token)).items.map((i) => i.venue)).toEqual(["Cafe", "Other"]);
  });

  it("validates the id and the body", async () => {
    const a = await newDevice("a");
    const bad: unknown[] = [
      { ...CHECKIN, venue: "" },
      { ...CHECKIN, venue: "   " },
      { ...CHECKIN, venue: "x".repeat(201) },
      { ...CHECKIN, venue: "a\u0000b" },
      { ...CHECKIN, venue: undefined },
      { ...CHECKIN, comment: "x".repeat(1001) },
      { ...CHECKIN, comment: "a\u0007b" },
      { ...CHECKIN, category: "x".repeat(201) },
      { ...CHECKIN, place: 3 },
      { ...CHECKIN, lat: 91 },
      { ...CHECKIN, lat: "35" },
      { ...CHECKIN, lon: -181 },
      { ...CHECKIN, lon: undefined },
      { ...CHECKIN, t: 1.5 },
      { ...CHECKIN, t: -1 },
      { ...CHECKIN, t: undefined },
      null,
    ];
    for (const body of bad) {
      expect((await putCheckin(a.token, "c1", body)).status, JSON.stringify(body)).toBe(400);
    }
    for (const localId of ["a.b", "x".repeat(65), "%E3%81%82"]) {
      expect((await putCheckin(a.token, localId, CHECKIN)).status, localId).toBe(400);
    }
    // Limits are inclusive.
    const max = { ...CHECKIN, venue: "x".repeat(200), comment: "x".repeat(1000), category: "x".repeat(200), place: "x".repeat(200) };
    expect((await putCheckin(a.token, "x".repeat(64), max)).status).toBe(200);

    expect(
      (await authed(a.token, "/v1/checkins/shared/c1", { method: "PUT", body: JSON.stringify(CHECKIN) })).status,
    ).toBe(400);
    const big = await authed(a.token, "/v1/checkins/shared/c1", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...CHECKIN, pad: "x".repeat(5000) }),
    });
    expect(big.status).toBe(413);
    expect((await feed(a.token)).items).toHaveLength(1);
  });

  it("requires a device of a user", async () => {
    expect((await call("/v1/checkins/feed")).status).toBe(401);
    expect((await putCheckin("A".repeat(43), "c1", CHECKIN)).status).toBe(401);
    const old = await legacyDevice("legacy1");
    expect((await putCheckin(old.token, "c1", CHECKIN)).status).toBe(403);
    const list = await authed(old.token, "/v1/checkins/feed");
    expect(list.status).toBe(403);
    expect(await list.json()).toEqual({ error: "no user" });
    expect((await unshare(old.token, "c1")).status).toBe(403);
  });
});

describe("DELETE /v1/checkins/shared/:localId", () => {
  it("leaves a tombstone without the content and moves it to a new cursor", async () => {
    const a = await newDevice("a");
    const { id, cursor } = (await (await putCheckin(a.token, "c1", { ...CHECKIN, comment: "hi", place: "p" })).json()) as {
      id: string;
      cursor: number;
    };
    expect((await unshare(a.token, "c1")).status).toBe(200);

    const { items } = await feed(a.token, `?after=${cursor}`);
    expect(items).toEqual([
      expect.objectContaining({ id, t: null, lat: null, lon: null, venue: null, comment: null, place: null, deleted: true }),
    ]);
    const row = await storedRow("c1");
    expect(row).toMatchObject({ t: null, lat: null, lon: null, venue: null, category: null, place: null, comment: null });
    expect(row.deleted_at).toEqual(expect.any(Number));

    // Unsharing again is a no-op; the cursor stays.
    expect((await unshare(a.token, "c1")).status).toBe(200);
    expect((await feed(a.token, `?after=${items[0]!.cursor}`)).items).toEqual([]);

    // Sharing again revives the same entry.
    await putCheckin(a.token, "c1", CHECKIN);
    expect((await feed(a.token, `?after=${items[0]!.cursor}`)).items).toEqual([
      expect.objectContaining({ id, venue: "Cafe", deleted: false }),
    ]);
  });

  it("404s for unknown ids and for other devices' check-ins", async () => {
    const alice = await newUser("alice");
    const a2 = await newDevice("a2", alice.userId);
    await putCheckin(alice.token, "c1", CHECKIN);
    expect((await unshare(alice.token, "nope")).status).toBe(404);
    expect((await unshare(alice.token, "a.b")).status).toBe(404);
    expect((await unshare(a2.token, "c1")).status).toBe(404);
    expect((await feed(alice.token)).items[0]!.deleted).toBe(false);
  });
});

describe("GET /v1/checkins/feed", () => {
  it("lists own and followed users' check-ins by cursor with pagination", async () => {
    const alice = await newUser("alice");
    const bob = await newUser("bob");
    const carol = await newUser("carol");
    await follow(bob, alice);
    await putCheckin(alice.token, "1", { ...CHECKIN, venue: "A1" });
    await putCheckin(bob.token, "1", { ...CHECKIN, venue: "B1" });
    await putCheckin(carol.token, "1", { ...CHECKIN, venue: "C1" });
    await putCheckin(alice.token, "2", { ...CHECKIN, venue: "A2" });

    expect((await feed(bob.token)).items.map((i) => [i.venue, i.user.name])).toEqual([
      ["A1", "alice"],
      ["B1", "bob"],
      ["A2", "alice"],
    ]);
    // Following is one-way; unrelated users see only their own.
    expect((await feed(alice.token)).items.map((i) => i.venue)).toEqual(["A1", "A2"]);
    expect((await feed(carol.token)).items.map((i) => i.venue)).toEqual(["C1"]);

    const seen: string[] = [];
    let after = 0;
    for (;;) {
      const page = await feed(bob.token, `?after=${after}&limit=2`);
      seen.push(...page.items.map((i) => i.venue!));
      if (page.next === null) break;
      after = page.next;
    }
    expect(seen).toEqual(["A1", "B1", "A2"]);
  });

  it("validates query parameters and caps the limit at 500", async () => {
    const a = await newDevice("a");
    expect((await authed(a.token, "/v1/checkins/feed?after=-1")).status).toBe(400);
    expect((await authed(a.token, "/v1/checkins/feed?after=x")).status).toBe(400);
    expect((await authed(a.token, "/v1/checkins/feed?limit=abc")).status).toBe(400);
    expect((await authed(a.token, "/v1/checkins/feed?limit=99999")).status).toBe(200);
  });

  it("hides a disabled user's entries, also from incremental listings, and restores them on enable", async () => {
    const alice = await newUser("alice");
    const bob = await newUser("bob");
    await follow(bob, alice);
    await putCheckin(alice.token, "1", { ...CHECKIN, venue: "A1", comment: "c" });
    await putCheckin(alice.token, "2", { ...CHECKIN, venue: "A2" });
    await putCheckin(bob.token, "1", { ...CHECKIN, venue: "B1" });
    const synced = await feed(bob.token);
    const last = synced.items[synced.items.length - 1]!.cursor;

    await adminCall(`/admin/users/${alice.userId}/disable`, { method: "POST" });
    const delta = await feed(bob.token, `?after=${last}`);
    // Disabling does not change whom bob follows; the entries come as deletions.
    expect(delta.followsVersion).toBe(synced.followsVersion);
    expect(delta.items.map((i) => [i.user.name, i.deleted, i.venue, i.comment])).toEqual([
      ["alice", true, null, null],
      ["alice", true, null, null],
    ]);
    expect((await feed(bob.token)).items.filter((i) => !i.deleted).map((i) => i.venue)).toEqual(["B1"]);
    // The stored content is kept, so enabling restores it.
    expect((await storedRow("2")).venue).toBe("A2");

    const lastDisabled = delta.items[delta.items.length - 1]!.cursor;
    await adminCall(`/admin/users/${alice.userId}/enable`, { method: "POST" });
    const restored = (await feed(bob.token, `?after=${lastDisabled}`)).items;
    expect(restored.map((i) => [i.venue, i.deleted])).toEqual([
      ["A1", false],
      ["A2", false],
    ]);
    // Cursors stay unique and increasing.
    const cursors = (await feed(bob.token)).items.map((i) => i.cursor);
    expect(cursors).toEqual([...cursors].sort((x, y) => x - y));
    expect(new Set(cursors).size).toBe(cursors.length);
  });
});

describe("admin: shared check-ins", () => {
  it("lists recent check-ins with the user and deletes one", async () => {
    const alice = await newUser("alice");
    const bob = await newUser("bob");
    await follow(bob, alice);
    await putCheckin(alice.token, "1", { ...CHECKIN, venue: "Old" });
    await putCheckin(alice.token, "2", { ...CHECKIN, venue: "New", comment: "spam" });
    await putCheckin(alice.token, "3", { ...CHECKIN, venue: "Gone" });
    await unshare(alice.token, "3");

    const res = await adminCall("/admin/checkins");
    expect(res.status).toBe(200);
    const list = (await res.json()) as Array<{ id: string; venue: string; user: { name: string }; userStatus: string }>;
    // Newest first, without unshared ones.
    expect(list.map((i) => [i.venue, i.user.name, i.userStatus])).toEqual([
      ["New", "alice", "active"],
      ["Old", "alice", "active"],
    ]);

    const before = (await feed(bob.token)).items;
    const lastCursor = before[before.length - 1]!.cursor;
    expect((await adminCall(`/admin/checkins/${list[0]!.id}/delete`, { method: "POST" })).status).toBe(200);
    expect((await (await adminCall("/admin/checkins")).json()) as unknown[]).toHaveLength(1);
    // Followers learn about it incrementally.
    expect((await feed(bob.token, `?after=${lastCursor}`)).items).toEqual([
      expect.objectContaining({ id: list[0]!.id, deleted: true, venue: null, comment: null }),
    ]);
    expect((await storedRow("2")).comment).toBeNull();

    // Idempotent; unknown ids are a 404.
    expect((await adminCall(`/admin/checkins/${list[0]!.id}/delete`, { method: "POST" })).status).toBe(200);
    expect((await adminCall("/admin/checkins/nope/delete", { method: "POST" })).status).toBe(404);
  });

  it("shows a disabled user's entries to the admin with their status", async () => {
    const alice = await newUser("alice");
    await putCheckin(alice.token, "1", CHECKIN);
    await adminCall(`/admin/users/${alice.userId}/disable`, { method: "POST" });
    const list = (await (await adminCall("/admin/checkins")).json()) as Array<{ venue: string; userStatus: string }>;
    expect(list.map((i) => [i.venue, i.userStatus])).toEqual([["Cafe", "disabled"]]);
  });

  it("requires an admin", async () => {
    const a = await newDevice("a");
    await putCheckin(a.token, "1", CHECKIN);
    const prod = { ALLOW_DEV_ADMIN: "false" };
    expect((await call("/admin/checkins", {}, prod)).status).toBe(401);
    expect((await authed(a.token, "/admin/checkins")).status).toBe(401);
    const id = (await feed(a.token)).items[0]!.id;
    expect((await call(`/admin/checkins/${id}/delete`, { method: "POST" }, prod)).status).toBe(401);
    expect((await authed(a.token, `/admin/checkins/${id}/delete`, { method: "POST" })).status).toBe(401);
    expect((await feed(a.token)).items[0]!.deleted).toBe(false);
  });
});

import { describe, expect, it } from "vitest";
import {
  adminCall,
  authed,
  CHECKIN,
  feed,
  follow,
  legacyDevice,
  newDevice,
  newUser,
  postJson,
  putCheckin,
} from "./helpers";

function lookup(token: string, username: string, ip = "192.0.2.1"): Promise<Response> {
  return authed(token, `/v1/users/lookup?username=${encodeURIComponent(username)}`, { headers: { "CF-Connecting-IP": ip } });
}

async function getJson<T>(token: string, path: string): Promise<T> {
  const res = await authed(token, path);
  expect(res.status, path).toBe(200);
  return (await res.json()) as T;
}

describe("GET /v1/users/lookup", () => {
  it("finds active users by exact, case-insensitive username only", async () => {
    const alice = await newUser("Alice", "alice");
    const bob = await newUser("Bob", "bob_2");
    const res = await lookup(alice.token, "BOB_2");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ id: bob.userId, username: "bob_2" });

    for (const h of ["bob", "bob_", "b%", "bob_2 x", ""]) {
      expect((await lookup(alice.token, h)).status, h).toBe(404);
    }
    expect((await authed(alice.token, "/v1/users/lookup")).status).toBe(404);

    await adminCall(`/admin/users/${bob.userId}/disable`, { method: "POST" });
    expect((await lookup(alice.token, "bob_2")).status).toBe(404);
  });

  it("is rate limited per IP", async () => {
    const alice = await newUser("alice", "alice");
    for (let i = 0; i < 20; i++) expect((await lookup(alice.token, "nobody", "198.51.100.9")).status).toBe(404);
    expect((await lookup(alice.token, "alice", "198.51.100.9")).status).toBe(429);
    expect((await lookup(alice.token, "alice", "198.51.100.10")).status).toBe(200);
  });
});

describe("follow requests", () => {
  it("request, list, accept, unfollow", async () => {
    const alice = await newUser("alice", "alice");
    const bob = await newUser("bob", "bob");

    const req = await postJson(bob.token, "/v1/follows", { userId: alice.userId });
    expect(req.status).toBe(200);
    expect(await req.json()).toEqual({ user: { id: alice.userId, username: "alice" }, status: "pending" });
    // Idempotent.
    expect(await (await postJson(bob.token, "/v1/follows", { userId: alice.userId })).json()).toMatchObject({
      status: "pending",
    });

    expect(await getJson(bob.token, "/v1/follows")).toEqual([
      { id: alice.userId, username: "alice", status: "pending", since: expect.any(Number) },
    ]);
    expect(await getJson(alice.token, "/v1/follow-requests")).toEqual([
      { id: bob.userId, username: "bob", since: expect.any(Number) },
    ]);
    expect(await getJson(alice.token, "/v1/followers")).toEqual([]);

    expect((await postJson(alice.token, `/v1/follow-requests/${bob.userId}/accept`)).status).toBe(200);
    // Accepting twice is fine; only the followee can accept.
    expect((await postJson(alice.token, `/v1/follow-requests/${bob.userId}/accept`)).status).toBe(200);
    expect((await postJson(bob.token, `/v1/follow-requests/${alice.userId}/accept`)).status).toBe(404);

    const following = await getJson<Array<{ status: string; since: number }>>(bob.token, "/v1/follows");
    expect(following.map((f) => f.status)).toEqual(["accepted"]);
    expect(following[0]!.since % (60 * 60 * 1000)).toBe(0);
    expect(await getJson(alice.token, "/v1/follow-requests")).toEqual([]);
    expect((await getJson<Array<{ id: string }>>(alice.token, "/v1/followers")).map((f) => f.id)).toEqual([bob.userId]);
    // Re-requesting an accepted follow keeps it.
    expect(await (await postJson(bob.token, "/v1/follows", { userId: alice.userId })).json()).toMatchObject({
      status: "accepted",
    });

    expect((await authed(bob.token, `/v1/follows/${alice.userId}`, { method: "DELETE" })).status).toBe(200);
    expect((await authed(bob.token, `/v1/follows/${alice.userId}`, { method: "DELETE" })).status).toBe(404);
    expect(await getJson(alice.token, "/v1/followers")).toEqual([]);
  });

  it("declining deletes the request, which can be sent again; requests can be cancelled", async () => {
    const alice = await newUser("alice");
    const bob = await newUser("bob");
    await postJson(bob.token, "/v1/follows", { userId: alice.userId });
    expect((await postJson(alice.token, `/v1/follow-requests/${bob.userId}/decline`)).status).toBe(200);
    expect((await postJson(alice.token, `/v1/follow-requests/${bob.userId}/decline`)).status).toBe(404);
    expect(await getJson(bob.token, "/v1/follows")).toEqual([]);

    await postJson(bob.token, "/v1/follows", { userId: alice.userId });
    expect((await getJson<unknown[]>(alice.token, "/v1/follow-requests")).length).toBe(1);
    // Cancel.
    expect((await authed(bob.token, `/v1/follows/${alice.userId}`, { method: "DELETE" })).status).toBe(200);
    expect(await getJson(alice.token, "/v1/follow-requests")).toEqual([]);
    expect((await postJson(alice.token, `/v1/follow-requests/${bob.userId}/accept`)).status).toBe(404);

    // Decline does not remove an accepted follower; remove-follower does not touch requests.
    await follow(bob, alice);
    expect((await postJson(alice.token, `/v1/follow-requests/${bob.userId}/decline`)).status).toBe(404);
    const carol = await newUser("carol");
    await postJson(carol.token, "/v1/follows", { userId: alice.userId });
    expect((await authed(alice.token, `/v1/followers/${carol.userId}`, { method: "DELETE" })).status).toBe(404);
  });

  it("validates requests", async () => {
    const alice = await newUser("alice");
    const bob = await newUser("bob");
    expect((await postJson(alice.token, "/v1/follows", { userId: alice.userId })).status).toBe(400);
    expect((await postJson(alice.token, "/v1/follows", { userId: "nope" })).status).toBe(404);
    expect((await postJson(alice.token, "/v1/follows", {})).status).toBe(400);
    expect((await postJson(alice.token, "/v1/follows", { userId: 1 })).status).toBe(400);
    await adminCall(`/admin/users/${bob.userId}/disable`, { method: "POST" });
    expect((await postJson(alice.token, "/v1/follows", { userId: bob.userId })).status).toBe(404);
  });

  it("hides disabled users from the lists", async () => {
    const alice = await newUser("alice");
    const bob = await newUser("bob");
    const carol = await newUser("carol");
    await follow(bob, alice);
    await postJson(carol.token, "/v1/follows", { userId: alice.userId });
    await adminCall(`/admin/users/${bob.userId}/disable`, { method: "POST" });
    await adminCall(`/admin/users/${carol.userId}/disable`, { method: "POST" });
    expect(await getJson(alice.token, "/v1/followers")).toEqual([]);
    expect(await getJson(alice.token, "/v1/follow-requests")).toEqual([]);
    // A disabled user's request cannot be accepted.
    expect((await postJson(alice.token, `/v1/follow-requests/${carol.userId}/accept`)).status).toBe(404);
  });

  it("is not available to legacy devices", async () => {
    const alice = await newUser("alice", "alice");
    const old = await legacyDevice("legacy1");
    const calls: Array<[string, RequestInit]> = [
      ["/v1/users/lookup?username=alice", {}],
      ["/v1/follows", {}],
      ["/v1/follows", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ userId: alice.userId }) }],
      [`/v1/follows/${alice.userId}`, { method: "DELETE" }],
      ["/v1/follow-requests", {}],
      [`/v1/follow-requests/${alice.userId}/accept`, { method: "POST" }],
      [`/v1/follow-requests/${alice.userId}/decline`, { method: "POST" }],
      ["/v1/followers", {}],
      [`/v1/followers/${alice.userId}`, { method: "DELETE" }],
    ];
    for (const [path, init] of calls) {
      expect((await authed(old.token, path, init)).status, path).toBe(403);
    }
  });
});

describe("feed and follow changes", () => {
  it("only accepted follows count", async () => {
    const alice = await newUser("alice");
    const bob = await newUser("bob");
    await putCheckin(alice.token, "1", CHECKIN);
    await postJson(bob.token, "/v1/follows", { userId: alice.userId });
    expect((await feed(bob.token)).items).toEqual([]);
    await postJson(alice.token, `/v1/follow-requests/${bob.userId}/accept`);
    expect((await feed(bob.token)).items.map((i) => i.user.id)).toEqual([alice.userId]);
  });

  it("changes followsVersion on accept, unfollow and removal, so incremental clients resync", async () => {
    const alice = await newUser("alice");
    const bob = await newUser("bob");
    const carol = await newUser("carol");
    // Alice's entries are older than anything bob has synced.
    await putCheckin(alice.token, "1", { ...CHECKIN, venue: "A1" });
    await putCheckin(bob.token, "1", { ...CHECKIN, venue: "B1" });
    const v0 = await feed(bob.token);
    const last = v0.items[v0.items.length - 1]!.cursor;

    // A pending request changes nothing.
    await postJson(bob.token, "/v1/follows", { userId: alice.userId });
    expect((await feed(bob.token, `?after=${last}`)).followsVersion).toBe(v0.followsVersion);

    // Accept: the version changes; resyncing from 0 brings alice's old entry.
    await postJson(alice.token, `/v1/follow-requests/${bob.userId}/accept`);
    const inc = await feed(bob.token, `?after=${last}`);
    expect(inc.items).toEqual([]);
    expect(inc.followsVersion).not.toBe(v0.followsVersion);
    const v1 = await feed(bob.token);
    expect(v1.items.map((i) => i.venue)).toEqual(["A1", "B1"]);

    // Unrelated follows (carol following bob) do not change bob's version.
    await follow(carol, bob);
    expect((await feed(bob.token)).followsVersion).toBe(v1.followsVersion);

    // Removal by the followee.
    await authed(alice.token, `/v1/followers/${bob.userId}`, { method: "DELETE" });
    const v2 = await feed(bob.token);
    expect(v2.followsVersion).not.toBe(v1.followsVersion);
    expect(v2.items.map((i) => i.venue)).toEqual(["B1"]);

    // Unfollow by the follower.
    await follow(bob, alice);
    const v3 = await feed(bob.token);
    expect(v3.items.map((i) => i.venue)).toEqual(["A1", "B1"]);
    await authed(bob.token, `/v1/follows/${alice.userId}`, { method: "DELETE" });
    const v4 = await feed(bob.token);
    expect(v4.followsVersion).not.toBe(v3.followsVersion);
    expect(v4.items.map((i) => i.venue)).toEqual(["B1"]);
  });

  it("keeps devices of the same user in one feed", async () => {
    const alice = await newUser("alice");
    const a2 = await newDevice("a2", alice.userId);
    await putCheckin(a2.token, "1", CHECKIN);
    expect((await feed(alice.token)).items).toHaveLength(1);
  });
});

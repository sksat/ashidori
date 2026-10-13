import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import {
  adminCall,
  authed,
  CHECKIN,
  feed,
  newDevice,
  newUser,
  postJson,
  putCheckin,
  unshare,
} from "./helpers";

function unshareById(token: string, id: string, query = ""): Promise<Response> {
  return authed(token, `/v1/checkins/shared/by-id/${id}${query}`, { method: "DELETE" });
}

const share = async (token: string, localId: string, body: Record<string, unknown>) => {
  const res = await putCheckin(token, localId, { ...CHECKIN, ...body });
  return { status: res.status, body: (await res.json()) as { id: string; cursor: number; error?: string } };
};

describe("revisions of shared check-ins", () => {
  it("refuse a stale PUT after a newer one", async () => {
    const a = await newDevice("a");
    expect((await share(a.token, "c1", { rev: 10, venue: "new" })).status).toBe(200);
    const stale = await share(a.token, "c1", { rev: 5, venue: "old" });
    expect(stale).toEqual({ status: 409, body: { error: "stale revision" } });
    // Same rev with other content is stale too.
    expect((await share(a.token, "c1", { rev: 10, venue: "other" })).status).toBe(409);
    expect((await feed(a.token)).items.map((i) => i.venue)).toEqual(["new"]);
  });

  it("accept a retry of the same PUT without moving the cursor", async () => {
    const a = await newDevice("a");
    const first = await share(a.token, "c1", { rev: 10 });
    const retry = await share(a.token, "c1", { rev: 10 });
    expect(retry).toEqual(first);
    // A newer rev with the same content records the rev, without a new cursor.
    const bumped = await share(a.token, "c1", { rev: 11 });
    expect(bumped).toEqual(first);
    expect((await share(a.token, "c1", { rev: 10 })).status).toBe(409);
  });

  it("do not let a delayed PUT resurrect an unshared entry", async () => {
    const a = await newDevice("a");
    const { body } = await share(a.token, "c1", { rev: 100 });
    // The response was lost; the user unshares (rev 200); then the retry arrives.
    expect((await unshare(a.token, "c1", "?rev=200")).status).toBe(200);
    expect((await share(a.token, "c1", { rev: 100 })).status).toBe(409);
    // An edit made before the unshare, arriving late, is refused as well.
    expect((await share(a.token, "c1", { rev: 150, venue: "edited" })).status).toBe(409);
    const items = (await feed(a.token)).items;
    expect(items).toEqual([expect.objectContaining({ id: body.id, deleted: true, venue: null })]);
    // Sharing again later (higher rev) is allowed.
    expect((await share(a.token, "c1", { rev: 300 })).status).toBe(200);
    expect((await feed(a.token)).items[0]!.deleted).toBe(false);
  });

  it("use the server time for an unshare without rev", async () => {
    const a = await newDevice("a");
    await share(a.token, "c1", { rev: 1 });
    const before = Date.now();
    expect((await unshare(a.token, "c1")).status).toBe(200);
    const row = await env.DB.prepare("SELECT rev FROM shared_checkins").first<{ rev: number }>();
    expect(row!.rev).toBeGreaterThanOrEqual(before);
    expect((await share(a.token, "c1", { rev: before - 1 })).status).toBe(409);
    // A second unshare with a higher rev raises it, without a new cursor.
    const cursor = (await feed(a.token)).items[0]!.cursor;
    expect((await unshare(a.token, "c1", `?rev=${row!.rev + 1000}`)).status).toBe(200);
    expect((await feed(a.token)).items[0]!.cursor).toBe(cursor);
    expect((await env.DB.prepare("SELECT rev FROM shared_checkins").first<{ rev: number }>())!.rev).toBe(row!.rev + 1000);
    // A lower rev never lowers it.
    await unshare(a.token, "c1", "?rev=1");
    expect((await env.DB.prepare("SELECT rev FROM shared_checkins").first<{ rev: number }>())!.rev).toBe(row!.rev + 1000);
  });

  it("validate rev on DELETE", async () => {
    const a = await newDevice("a");
    await share(a.token, "c1", { rev: 1 });
    for (const q of ["?rev=-1", "?rev=x", "?rev=1.5", "?rev=01"]) expect((await unshare(a.token, "c1", q)).status, q).toBe(400);
  });

  it("make removal by an admin final for that entry", async () => {
    const a = await newDevice("a");
    const { body } = await share(a.token, "c1", { rev: 100 });
    expect((await adminCall(`/admin/checkins/${body.id}/delete`, { method: "POST" })).status).toBe(200);
    for (const rev of [100, 101, Number.MAX_SAFE_INTEGER]) {
      const res = await share(a.token, "c1", { rev });
      expect(res).toEqual({ status: 409, body: { error: "removed by an admin" } });
    }
    // The owner may still unshare it (a no-op), and share other check-ins.
    expect((await unshare(a.token, "c1")).status).toBe(200);
    expect((await share(a.token, "c2", { rev: 1 })).status).toBe(200);
    expect((await feed(a.token)).items.map((i) => i.deleted)).toEqual([true, false]);
  });

  it("an admin can also make an already unshared entry final", async () => {
    const a = await newDevice("a");
    const { body } = await share(a.token, "c1", { rev: 1 });
    await unshare(a.token, "c1", "?rev=2");
    expect((await adminCall(`/admin/checkins/${body.id}/delete`, { method: "POST" })).status).toBe(200);
    expect((await share(a.token, "c1", { rev: 3 })).status).toBe(409);
  });
});

describe("DELETE /v1/checkins/shared/by-id/:id", () => {
  it("unshares from any device of the user, also after the sharing device was revoked", async () => {
    const alice = await newUser("alice");
    const lost = await newDevice("lost", alice.userId);
    const { body } = await share(lost.token, "c1", { rev: 1 });
    expect((await postJson(alice.token, `/v1/devices/${lost.deviceId}/revoke`)).status).toBe(200);

    const res = await unshareById(alice.token, body.id);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ deleted: true });
    const items = (await feed(alice.token)).items;
    expect(items).toEqual([expect.objectContaining({ id: body.id, deleted: true, venue: null })]);
    expect(items[0]!.cursor).toBeGreaterThan(body.cursor);
    // Idempotent.
    expect((await unshareById(alice.token, body.id)).status).toBe(200);
  });

  it("does not let other users unshare", async () => {
    const alice = await newUser("alice");
    const bob = await newUser("bob");
    const { body } = await share(alice.token, "c1", { rev: 1 });
    expect((await unshareById(bob.token, body.id)).status).toBe(404);
    expect((await unshareById(alice.token, "nope")).status).toBe(404);
    expect((await feed(alice.token)).items[0]!.deleted).toBe(false);
  });

  it("blocks a stale PUT from the original device", async () => {
    const alice = await newUser("alice");
    const other = await newDevice("other", alice.userId);
    const { body } = await share(alice.token, "c1", { rev: 1000 });
    expect((await unshareById(other.token, body.id, "?rev=2000")).status).toBe(200);
    expect((await share(alice.token, "c1", { rev: 1000 })).status).toBe(409);
    expect((await share(alice.token, "c1", { rev: 1500, venue: "edit" })).status).toBe(409);
    expect((await share(alice.token, "c1", { rev: 2500 })).status).toBe(200);
  });
});

describe("shared check-in limit", () => {
  it("counts entries per user, tombstones included, and still allows updates", async () => {
    const alice = await newUser("alice");
    const a2 = await newDevice("a2", alice.userId);
    const LIMIT = { USER_MAX_SHARED_CHECKINS: "2" };
    const put = (token: string, id: string, rev: number) =>
      authed(token, `/v1/checkins/shared/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...CHECKIN, rev }),
      }, LIMIT);
    expect((await put(alice.token, "c1", 1)).status).toBe(200);
    expect((await put(a2.token, "c1", 1)).status).toBe(200);
    const full = await put(alice.token, "c2", 1);
    expect(full.status).toBe(507);
    expect(await full.json()).toEqual({ error: "too many shared check-ins" });
    // Unsharing keeps a tombstone, which still counts.
    await unshare(alice.token, "c1", "?rev=2");
    expect((await put(alice.token, "c2", 1)).status).toBe(507);
    // Existing entries can still be changed and reshared.
    expect((await put(alice.token, "c1", 3)).status).toBe(200);
    expect((await put(a2.token, "c1", 2)).status).toBe(200);
    // Other users are unaffected.
    const bob = await newUser("bob");
    expect((await put(bob.token, "c1", 1)).status).toBe(200);
    const counts = await env.DB.prepare("SELECT name, checkin_count FROM users ORDER BY name").all();
    expect(counts.results).toEqual([
      { name: "alice", checkin_count: 2 },
      { name: "bob", checkin_count: 1 },
    ]);
  });

  it("refuses new entries while a limit is invalid", async () => {
    const a = await newDevice("a");
    const res = await authed(a.token, "/v1/checkins/shared/c1", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(CHECKIN),
    }, { USER_MAX_SHARED_CHECKINS: "0" });
    expect(res.status).toBe(503);
  });
});

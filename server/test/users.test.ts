import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { adminCall, authed, blob, createUser, issueAdminCode, legacyDevice, newDevice, putBatch, register } from "./helpers";

interface ListItem {
  cursor: number;
  deviceId: string;
  stream: string;
  seq: number;
}

async function listAll(token: string): Promise<ListItem[]> {
  const res = await authed(token, "/v1/batches?limit=1000");
  expect(res.status).toBe(200);
  return ((await res.json()) as { items: ListItem[] }).items;
}

async function deviceUser(id: string): Promise<string | null> {
  const row = await env.DB.prepare("SELECT user_id FROM devices WHERE id = ?1")
    .bind(id)
    .first<{ user_id: string | null }>();
  return row!.user_id;
}

describe("registration binds devices to users", () => {
  it("assigns the code's user, and device-issued codes inherit it", async () => {
    const alice = await createUser("alice");
    const res = await register(await issueAdminCode(alice), "a1");
    expect(res.status).toBe(201);
    const a1 = (await res.json()) as { deviceId: string; token: string };
    expect(await deviceUser(a1.deviceId)).toBe(alice);

    const pairing = await authed(a1.token, "/v1/pairing", { method: "POST" });
    const { code } = (await pairing.json()) as { code: string };
    const a2 = (await (await register(code, "a2")).json()) as { deviceId: string };
    expect(await deviceUser(a2.deviceId)).toBe(alice);
  });

  it("legacy devices create legacy codes and only see each other", async () => {
    const old = await legacyDevice("legacy1");
    const res = await authed(old.token, "/v1/pairing", { method: "POST" });
    expect(res.status).toBe(201);
    const { code } = (await res.json()) as { code: string };
    const added = (await (await register(code, "legacy2")).json()) as { deviceId: string; token: string };
    expect(await deviceUser(added.deviceId)).toBeNull();

    const alice = await createUser("alice");
    const a = await newDevice("a", alice);
    await putBatch(a.token, "fixes", 0, blob("alice"));
    await putBatch(old.token, "fixes", 0, blob("legacy"));

    const devices = (await (await authed(added.token, "/v1/devices")).json()) as Array<{ id: string }>;
    expect(devices.map((d) => d.id).sort()).toEqual([added.deviceId, "legacy1"].sort());
    expect((await listAll(added.token)).map((i) => i.deviceId)).toEqual(["legacy1"]);
    expect((await authed(added.token, `/v1/batches/${a.deviceId}/fixes/0`)).status).toBe(404);
    expect((await authed(a.token, `/v1/batches/legacy1/fixes/0`)).status).toBe(404);
    expect((await listAll(a.token)).map((i) => i.deviceId)).toEqual([a.deviceId]);
    expect((await authed(a.token, `/v1/devices/legacy1/revoke`, { method: "POST" })).status).toBe(404);
  });
});

describe("per-user isolation", () => {
  it("users see only their own devices and batches", async () => {
    const alice = await createUser("alice");
    const bob = await createUser("bob");
    const a1 = await newDevice("a1", alice);
    const a2 = await newDevice("a2", alice);
    const b1 = await newDevice("b1", bob);

    expect((await putBatch(a1.token, "fixes", 0, blob("a1"))).status).toBe(201);
    expect((await putBatch(b1.token, "fixes", 0, blob("b1"))).status).toBe(201);
    expect((await putBatch(a2.token, "fixes", 0, blob("a2"))).status).toBe(201);
    expect((await putBatch(b1.token, "places", 0, blob("b1 places"))).status).toBe(201);

    // Devices.
    const aliceDevices = (await (await authed(a2.token, "/v1/devices")).json()) as Array<{ id: string }>;
    expect(aliceDevices.map((d) => d.id).sort()).toEqual([a1.deviceId, a2.deviceId].sort());
    const bobDevices = (await (await authed(b1.token, "/v1/devices")).json()) as Array<{ id: string }>;
    expect(bobDevices.map((d) => d.id)).toEqual([b1.deviceId]);

    // Listings.
    expect((await listAll(a1.token)).map((i) => [i.deviceId, i.stream])).toEqual([
      [a1.deviceId, "fixes"],
      [a2.deviceId, "fixes"],
    ]);
    expect((await listAll(b1.token)).map((i) => [i.deviceId, i.stream])).toEqual([
      [b1.deviceId, "fixes"],
      [b1.deviceId, "places"],
    ]);

    // Pagination stays within the user even with other users' batches in between.
    const page1 = (await (await authed(a1.token, "/v1/batches?limit=1")).json()) as {
      items: ListItem[];
      next: number;
    };
    expect(page1.items.map((i) => i.deviceId)).toEqual([a1.deviceId]);
    const page2 = (await (await authed(a1.token, `/v1/batches?after=${page1.next}&limit=1`)).json()) as {
      items: ListItem[];
      next: number | null;
    };
    expect(page2.items.map((i) => i.deviceId)).toEqual([a2.deviceId]);
    expect(page2.next).toBeNull();

    // Fetches: the user's other device works, another user's is a 404.
    expect((await authed(a1.token, `/v1/batches/${a2.deviceId}/fixes/0`)).status).toBe(200);
    expect((await authed(a1.token, `/v1/batches/${b1.deviceId}/fixes/0`)).status).toBe(404);
    expect((await authed(b1.token, `/v1/batches/${a1.deviceId}/fixes/0`)).status).toBe(404);

    // Revocation: not across users.
    expect((await authed(a1.token, `/v1/devices/${b1.deviceId}/revoke`, { method: "POST" })).status).toBe(404);
    expect((await authed(b1.token, "/v1/devices")).status).toBe(200);
    expect((await authed(a1.token, `/v1/devices/${a2.deviceId}/revoke`, { method: "POST" })).status).toBe(200);
    expect((await authed(a2.token, "/v1/devices")).status).toBe(401);
  });

  it("disabling one user does not affect another", async () => {
    const alice = await createUser("alice");
    const bob = await createUser("bob");
    const a = await newDevice("a", alice);
    const b = await newDevice("b", bob);
    await adminCall(`/admin/users/${alice}/disable`, { method: "POST" });
    expect((await putBatch(a.token, "fixes", 0, blob("x"))).status).toBe(401);
    expect((await authed(a.token, "/v1/pairing", { method: "POST" })).status).toBe(401);
    expect((await authed(a.token, "/v1/batches")).status).toBe(401);
    expect((await putBatch(b.token, "fixes", 0, blob("x"))).status).toBe(201);
  });
});

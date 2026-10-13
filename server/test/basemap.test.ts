import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { clearBasemapCache, parseRange } from "../src/basemap";
import { call } from "./helpers";

const V = "20261013";
const ZIP = `ashidori-basemap-${V}.zip`;
const DATA = new Uint8Array(1000).map((_, i) => (i * 7) & 0xff);

async function upload(version = V, file = `ashidori-basemap-${version}.zip`, data: Uint8Array = DATA): Promise<void> {
  await env.BLOBS.put(`basemap/${version}/${file}`, data);
  clearBasemapCache();
}

async function bytes(res: Response): Promise<Uint8Array> {
  return new Uint8Array(await res.arrayBuffer());
}

describe("GET /basemap/:version/:file", () => {
  it("serves the archive without auth or cookies, with ETag and Content-Length", async () => {
    await upload();
    const res = await call(`/basemap/${V}/${ZIP}`);
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Length")).toBe("1000");
    expect(res.headers.get("Content-Type")).toBe("application/zip");
    expect(res.headers.get("Accept-Ranges")).toBe("bytes");
    expect(res.headers.get("ETag")).toMatch(/^".+"$/);
    expect(res.headers.get("Set-Cookie")).toBeNull();
    expect(await bytes(res)).toEqual(DATA);
  });

  it("answers Range requests with 206 and Content-Range", async () => {
    await upload();
    const res = await call(`/basemap/${V}/${ZIP}`, { headers: { Range: "bytes=600-" } });
    expect(res.status).toBe(206);
    expect(res.headers.get("Content-Range")).toBe("bytes 600-999/1000");
    expect(res.headers.get("Content-Length")).toBe("400");
    expect(await bytes(res)).toEqual(DATA.slice(600));

    const mid = await call(`/basemap/${V}/${ZIP}`, { headers: { Range: "bytes=10-19" } });
    expect(mid.status).toBe(206);
    expect(mid.headers.get("Content-Range")).toBe("bytes 10-19/1000");
    expect(await bytes(mid)).toEqual(DATA.slice(10, 20));

    const tail = await call(`/basemap/${V}/${ZIP}`, { headers: { Range: "bytes=-5" } });
    expect(tail.headers.get("Content-Range")).toBe("bytes 995-999/1000");
    expect(await bytes(tail)).toEqual(DATA.slice(995));
  });

  it("answers 416 past the end, and ignores a range for another ETag (If-Range)", async () => {
    await upload();
    const past = await call(`/basemap/${V}/${ZIP}`, { headers: { Range: "bytes=1000-" } });
    expect(past.status).toBe(416);
    expect(past.headers.get("Content-Range")).toBe("bytes */1000");

    const stale = await call(`/basemap/${V}/${ZIP}`, { headers: { Range: "bytes=600-", "If-Range": '"other"' } });
    expect(stale.status).toBe(200);
    expect(await bytes(stale)).toEqual(DATA);
  });

  it("answers 304 for a matching If-None-Match", async () => {
    await upload();
    const etag = (await call(`/basemap/${V}/${ZIP}`)).headers.get("ETag")!;
    const res = await call(`/basemap/${V}/${ZIP}`, { headers: { "If-None-Match": etag } });
    expect(res.status).toBe(304);
  });

  it("serves the descriptor", async () => {
    await upload(V, `ashidori-basemap-${V}.json`, new TextEncoder().encode('{"version":"20261013"}'));
    const res = await call(`/basemap/${V}/ashidori-basemap-${V}.json`);
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("application/json");
  });

  it("returns 404 when the object is missing", async () => {
    const res = await call(`/basemap/${V}/${ZIP}`);
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: "not found" });
  });

  it("serves only the allowed names, never other keys", async () => {
    await env.BLOBS.put("b/dev1/small/000000000001", DATA);
    await env.BLOBS.put(`basemap/${V}/secret.txt`, DATA);
    await env.BLOBS.put(`basemap/${V}/ashidori-basemap-other.zip`, DATA);
    for (const path of [
      `/basemap/${V}/secret.txt`,
      `/basemap/${V}/ashidori-basemap-other.zip`,
      "/basemap/b/dev1",
      "/basemap/..%2Fb/dev1%2Fsmall%2F000000000001",
      "/basemap/../b/dev1/small/000000000001",
      `/basemap/${V}/..%2F..%2Fb%2Fdev1%2Fsmall%2F000000000001`,
      `/basemap/.${V}/ashidori-basemap-.${V}.zip`,
      "/basemap/b/dev1/small/000000000001",
    ]) {
      const res = await call(path);
      expect(res.status, path).toBe(404);
      const body = await bytes(res);
      expect(body, path).not.toEqual(DATA);
    }
  });
});

describe("parseRange", () => {
  it("handles single ranges and leaves the rest to a full response", () => {
    expect(parseRange(undefined, 10)).toBeNull();
    expect(parseRange("bytes=0-", 10)).toEqual({ offset: 0, length: 10 });
    expect(parseRange("bytes=2-100", 10)).toEqual({ offset: 2, length: 8 });
    expect(parseRange("bytes=-3", 10)).toEqual({ offset: 7, length: 3 });
    expect(parseRange("bytes=-30", 10)).toEqual({ offset: 0, length: 10 });
    expect(parseRange("bytes=10-", 10)).toBe("unsatisfiable");
    expect(parseRange("bytes=5-2", 10)).toBeNull();
    expect(parseRange("bytes=0-1,4-5", 10)).toBeNull();
    expect(parseRange("items=0-1", 10)).toBeNull();
  });
});

describe("GET /v1/info basemap", () => {
  it("lists the versions whose archive is stored", async () => {
    await upload("20261013");
    await upload("20261101");
    await upload("20261120", "ashidori-basemap-20261120.json");
    await env.BLOBS.put("basemap/x/ashidori-basemap-y.zip", DATA);
    clearBasemapCache();
    const info = (await (await call("/v1/info")).json()) as { registration: string; basemap: string[] };
    expect(info.registration).toBe("admin");
    expect(info.basemap).toEqual(["20261101", "20261013"]);
  });
});

import { describe, expect, it } from "vitest";
import { call, newDevice } from "./helpers";

describe("HTTP behaviour", () => {
  it("sets security headers", async () => {
    const res = await call("/v1/health");
    expect(res.headers.get("X-Content-Type-Options")).toBe("nosniff");
    expect(res.headers.get("Referrer-Policy")).toBe("no-referrer");
    expect(res.headers.get("X-Frame-Options")).toBe("DENY");
    expect(res.headers.get("Strict-Transport-Security")).toMatch(/max-age=/);
    expect(res.headers.get("Content-Security-Policy")).toMatch(/default-src 'none'/);
    expect(res.headers.get("Cache-Control")).toBe("no-store");
  });

  it("returns JSON 404s", async () => {
    const res = await call("/nope");
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: "not found" });
  });

  it("does not send CORS headers to unlisted origins", async () => {
    const res = await call("/v1/health", { headers: { Origin: "https://evil.example" } });
    expect(res.headers.get("Access-Control-Allow-Origin")).toBeNull();
    const pre = await call("/v1/batches", {
      method: "OPTIONS",
      headers: { Origin: "https://evil.example", "Access-Control-Request-Method": "GET" },
    });
    expect(pre.headers.get("Access-Control-Allow-Origin")).toBeNull();
  });

  it("sends CORS headers to the allow-listed viewer origin", async () => {
    const pre = await call("/v1/batches", {
      method: "OPTIONS",
      headers: { Origin: "https://viewer.example", "Access-Control-Request-Method": "GET" },
    });
    expect(pre.status).toBe(204);
    expect(pre.headers.get("Access-Control-Allow-Origin")).toBe("https://viewer.example");
    expect(pre.headers.get("Access-Control-Allow-Headers")).toMatch(/Authorization/);

    const a = await newDevice();
    const res = await call("/v1/batches", {
      headers: { Origin: "https://viewer.example", Authorization: `Bearer ${a.token}` },
    });
    expect(res.status).toBe(200);
    expect(res.headers.get("Access-Control-Allow-Origin")).toBe("https://viewer.example");
  });

  it("never sends CORS headers on admin routes", async () => {
    const res = await call("/admin/devices", { headers: { Origin: "https://viewer.example" } });
    expect(res.headers.get("Access-Control-Allow-Origin")).toBeNull();
  });
});

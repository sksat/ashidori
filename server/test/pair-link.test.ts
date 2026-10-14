import { afterEach, describe, expect, it, vi } from "vitest";
import { configErrors, parseCertFingerprints } from "../src/config";
import { ANDROID_PACKAGE, RELEASES_URL } from "../src/pair";
import { adminCall, call } from "./helpers";

afterEach(() => {
  vi.restoreAllMocks();
});

const FP_A = Array.from({ length: 32 }, (_, i) => i.toString(16).padStart(2, "0").toUpperCase()).join(":");
const FP_B = "FA:C6:17:45:DC:09:03:78:6F:B9:ED:E6:2A:96:2B:39:9F:73:48:F0:BB:6F:89:9B:83:32:66:75:91:03:3B:9C";

describe("GET /pair", () => {
  it("serves the static page with a nonce CSP, without the code", async () => {
    const res = await call("/pair");
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toMatch(/text\/html/);
    const csp = res.headers.get("Content-Security-Policy") ?? "";
    const nonce = /script-src 'nonce-([A-Za-z0-9_-]+)'/.exec(csp)?.[1];
    expect(nonce).toBeDefined();
    expect(csp).toMatch(/default-src 'none'/);
    expect(csp).toMatch(/frame-ancestors 'none'/);
    expect(csp).not.toMatch(/connect-src/);
    expect(res.headers.get("Referrer-Policy")).toBe("no-referrer");
    const html = await res.text();
    // Every script and style carries the nonce.
    expect(html.match(/<script/g)?.length).toBe(html.match(new RegExp(`<script nonce="${nonce}"`, "g"))?.length);
    expect(html).toContain(`<style nonce="${nonce}">`);
    // The code is read from the fragment, client-side, and checked strictly.
    expect(html).toContain("location.hash");
    expect(html).toContain("/^#c=([0-9ABCDEFGHJKMNPQRSTVWXYZ]{8})$/");
    expect(html).toContain('"ashidori://pair?server=" + encodeURIComponent(location.origin)');
    expect(html).toContain("アプリで開く");
    expect(html).toContain(RELEASES_URL);
  });

  it("uses a fresh nonce per response", async () => {
    const nonce = async () => /nonce-([A-Za-z0-9_-]+)/.exec((await call("/pair")).headers.get("Content-Security-Policy") ?? "")?.[1];
    expect(await nonce()).not.toBe(await nonce());
  });

  it("also serves /pair/ and ignores a query", async () => {
    expect((await call("/pair/")).status).toBe(200);
    expect((await call("/pair?x=1")).status).toBe(200);
  });

  it("matches the codes the server issues", async () => {
    const res = await adminCall("/admin/users", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: "alice" }),
    });
    const { id } = (await res.json()) as { id: string };
    const { code } = (await (await adminCall(`/admin/users/${id}/pairing`, { method: "POST" })).json()) as { code: string };
    expect(`#c=${code}`).toMatch(/^#c=([0-9ABCDEFGHJKMNPQRSTVWXYZ]{8})$/);
  });

  it("the admin page builds the registration link from the origin and the code", async () => {
    const html = await (await adminCall("/admin/")).text();
    expect(html).toContain('location.origin + "/pair#c=" + body.code');
  });
});

describe("GET /.well-known/assetlinks.json", () => {
  it("is [] without fingerprints", async () => {
    for (const v of [undefined, "", "  "]) {
      const res = await call("/.well-known/assetlinks.json", {}, { ANDROID_CERT_SHA256: v as string });
      expect(res.status).toBe(200);
      expect(res.headers.get("Content-Type")).toMatch(/application\/json/);
      expect(await res.json()).toEqual([]);
    }
  });

  it("lists the app's package with the fingerprints", async () => {
    const raw = `${FP_A.toLowerCase()} , ${FP_B.replaceAll(":", "")},${FP_A}`;
    const res = await call("/.well-known/assetlinks.json", {}, { ANDROID_CERT_SHA256: raw });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual([
      {
        relation: ["delegate_permission/common.handle_all_urls"],
        target: { namespace: "android_app", package_name: ANDROID_PACKAGE, sha256_cert_fingerprints: [FP_A, FP_B] },
      },
    ]);
    expect(ANDROID_PACKAGE).toBe("net.sksat.ashidori");
  });

  it("serves [] and logs when the variable is malformed", async () => {
    const err = vi.spyOn(console, "error").mockImplementation(() => {});
    const res = await call("/.well-known/assetlinks.json", {}, { ANDROID_CERT_SHA256: `${FP_A},nope` });
    expect(await res.json()).toEqual([]);
    expect(err).toHaveBeenCalled();
  });
});

describe("ANDROID_CERT_SHA256", () => {
  it("parses keytool and apksigner forms strictly", () => {
    expect(parseCertFingerprints(undefined)).toEqual([]);
    expect(parseCertFingerprints("")).toEqual([]);
    expect(parseCertFingerprints(FP_B)).toEqual([FP_B]);
    expect(parseCertFingerprints(FP_B.replaceAll(":", "").toLowerCase())).toEqual([FP_B]);
    for (const bad of [
      "nope",
      FP_B.slice(3),
      `${FP_B}:00`,
      `${FP_B},`,
      FP_B.replaceAll(":", "-"),
      FP_B.replace("FA", "FG"),
      // SHA-1 (20 bytes)
      FP_B.split(":").slice(0, 20).join(":"),
    ]) {
      expect(parseCertFingerprints(bad), bad).toBeNull();
    }
  });

  it("is checked before a deploy", () => {
    expect(configErrors({ ANDROID_CERT_SHA256: FP_B })).toEqual([]);
    expect(configErrors({ ANDROID_CERT_SHA256: "" })).toEqual([]);
    expect(configErrors({ ANDROID_CERT_SHA256: "AB:CD" })).toHaveLength(1);
  });
});

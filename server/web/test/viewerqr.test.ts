// The pairing QR code: its text is exactly what the app's ViewerQr parses
// (the fixture core's ViewerPairingFixtures writes), it is drawn with the
// bundled qrcode-generator, and the stepper starts with it.

import { describe, expect, it } from "vitest";
import { fromHex } from "../src/data/bytes";
import { canonicalOrigin, VIEWER_QR_PREFIX, viewerQrUri } from "../src/data/viewerqr";
import { qrText, STEPS, stepOf } from "../src/ui/pairing";
import { QUIET_ZONE, qrPath } from "../src/ui/qr";
import { fixtureJson } from "./helpers";

interface QrVector {
  server: string;
  id: string;
  code: string;
  c: string;
  uri: string;
}

const vectors = fixtureJson<{ qr: QrVector[]; sas: { c: string } }>("viewer-pairing.json");

describe("the pairing QR code's text", () => {
  it("is what the app parses: the fixture written by the app's code", () => {
    expect(vectors.qr.length).toBeGreaterThan(0);
    for (const v of vectors.qr) {
      expect(v.c).toBe(vectors.sas.c);
      expect(viewerQrUri(v.server, v.id, v.code, fromHex(v.c))).toBe(v.uri);
      expect(v.uri.startsWith(VIEWER_QR_PREFIX)).toBe(true);
    }
  });

  it("refuses values the app would refuse", () => {
    const v = vectors.qr[0]!;
    const c = fromHex(v.c);
    expect(() => viewerQrUri("https://ashidori.example/", v.id, v.code, c)).toThrow();
    expect(() => viewerQrUri("https://Ashidori.example", v.id, v.code, c)).toThrow();
    expect(() => viewerQrUri("ftp://ashidori.example", v.id, v.code, c)).toThrow();
    expect(() => viewerQrUri("null", v.id, v.code, c)).toThrow();
    expect(() => viewerQrUri(v.server, `${v.id}A`, v.code, c)).toThrow();
    expect(() => viewerQrUri(v.server, v.id, "ABCD-2345", c)).toThrow();
    expect(() => viewerQrUri(v.server, v.id, v.code, c.slice(1))).toThrow();
  });

  it("writes the origin without its default port; other ports stay", () => {
    const v = vectors.qr.find((x) => x.server.startsWith("https://"))!;
    const c = fromHex(v.c);
    expect(viewerQrUri(`${v.server}:443`, v.id, v.code, c)).toBe(v.uri);
    expect(viewerQrUri("http://localhost:80", v.id, v.code, c)).toBe(viewerQrUri("http://localhost", v.id, v.code, c));
    expect(viewerQrUri("http://localhost:443", v.id, v.code, c)).toContain("localhost%3A443");
    expect(viewerQrUri(`${v.server}:8443`, v.id, v.code, c)).toContain("%3A8443");
    expect(canonicalOrigin("https://ashidori.example:4430")).toBe("https://ashidori.example:4430");
  });

  it("the page's qrText gives null instead of throwing (the code still works)", () => {
    const v = vectors.qr[0]!;
    const e = { id: v.id, code: v.code, commit: fromHex(v.c) };
    expect(qrText(v.server, e)).toBe(v.uri);
    expect(qrText("null", e)).toBeNull();
  });
});

describe("the QR code's drawing", () => {
  it("draws the text with error correction M and a 4-module quiet zone", () => {
    const v = vectors.qr[0]!;
    const p = qrPath(v.uri);
    // A version 1..40 symbol: 17 + 4 × version modules.
    expect((p.modules - 17) % 4).toBe(0);
    expect(p.modules).toBeGreaterThanOrEqual(21);
    expect(p.size).toBe(p.modules + 2 * QUIET_ZONE);
    // The top-left finder pattern starts at the corner, after the quiet zone.
    expect(p.d.startsWith(`M${QUIET_ZONE} ${QUIET_ZONE}h1v1h-1z`)).toBe(true);
    // Deterministic, and another text draws another code.
    expect(qrPath(v.uri).d).toBe(p.d);
    expect(qrPath(v.uri.replace(v.code, "ZZZZ9999")).d).not.toBe(p.d);
  });
});

describe("the pairing stepper", () => {
  it("starts with reading the QR code; the rest is as before", () => {
    expect(STEPS).toEqual(["QR コードの読み取り", "確認コードの照合", "完了"]);
    expect(stepOf("intro")).toBe(1);
    expect(stepOf("code")).toBe(1);
    expect(stepOf("words")).toBe(2);
    expect(stepOf("done")).toBe(3);
  });
});

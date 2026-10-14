// The QR code of browser pairing (docs/design/browser-pairing-v1.md,
// 「QR コード」): the app's 「ブラウザでも見る」 scans it instead of the user
// typing the code. It carries the attempt and the commit c, so the app learns
// c from this browser through the camera, not from the server:
//
//   ashidori://viewer?server=<origin>&id=<id>&code=<code>&c=<commit, hex>
//
// The app (core ViewerQr) accepts exactly this form and nothing else: the
// parameters in this order, the origin percent-encoded as encodeURIComponent
// does, the id as the server sent it (base64url), the code as the server sent
// it (8 Crockford characters), c in lower-case hex. Nothing in it is secret
// (the server knows all of it); the camera only keeps it unchanged.

import { hex } from "./bytes";
import { CODE_RE, KEY_LEN } from "./paircrypto";

export const VIEWER_QR_PREFIX = "ashidori://viewer?";
const ORIGIN_RE = /^https?:\/\/[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*(:[0-9]{1,5})?$/;
const ID_RE = /^[A-Za-z0-9_-]{22}$/;

/**
 * The origin as location.origin writes it: without the default port (443
 * for https, 80 for http). The app compares scheme, host and effective
 * port, so either form matches its setting; this keeps one form in the QR.
 */
export function canonicalOrigin(origin: string): string {
  return origin.replace(/^(https:\/\/[^/]+):443$/, "$1").replace(/^(http:\/\/[^/]+):80$/, "$1");
}

/** The text of the QR code; throws on a value the app would refuse. */
export function viewerQrUri(pageOrigin: string, id: string, code: string, commit: Uint8Array): string {
  const origin = canonicalOrigin(pageOrigin);
  if (!ORIGIN_RE.test(origin)) throw new Error("the page's origin cannot be put in the QR code");
  if (!ID_RE.test(id)) throw new Error("malformed id");
  if (!CODE_RE.test(code)) throw new Error("malformed code");
  if (commit.byteLength !== KEY_LEN) throw new Error("the commit must be 32 bytes");
  return `${VIEWER_QR_PREFIX}server=${encodeURIComponent(origin)}&id=${id}&code=${code}&c=${hex(commit)}`;
}

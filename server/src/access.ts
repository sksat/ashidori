// Verification of Cloudflare Access JWTs (`Cf-Access-Jwt-Assertion`).
//
// Access already blocks unauthenticated requests at the edge, but the Worker
// verifies the token itself so that a missing or misconfigured Access
// application never exposes /admin/*.

import { base64UrlDecode } from "./util";

interface Jwk {
  kid: string;
  kty: string;
  alg?: string;
  n: string;
  e: string;
}

interface CertCache {
  url: string;
  keys: Map<string, CryptoKey>;
  fetchedAt: number;
}

const CERT_TTL_MS = 60 * 60 * 1000;
const CERT_REFRESH_MIN_INTERVAL_MS = 60 * 1000;
const CLOCK_SKEW_S = 60;

let cache: CertCache | null = null;

/** For tests. */
export function clearAccessCertCache(): void {
  cache = null;
}

export function normalizeTeamDomain(domain: string): string {
  return domain.trim().replace(/^https?:\/\//, "").replace(/\/+$/, "");
}

async function loadCerts(url: string): Promise<Map<string, CryptoKey>> {
  const res = await fetch(url, { headers: { accept: "application/json" } });
  if (!res.ok) throw new Error(`failed to fetch Access certs: ${res.status}`);
  const body = (await res.json()) as { keys?: Jwk[] };
  const keys = new Map<string, CryptoKey>();
  for (const jwk of body.keys ?? []) {
    if (jwk.kty !== "RSA" || !jwk.kid) continue;
    const key = await crypto.subtle.importKey(
      "jwk",
      { kty: "RSA", n: jwk.n, e: jwk.e, alg: "RS256", ext: true },
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"],
    );
    keys.set(jwk.kid, key);
  }
  return keys;
}

async function getKey(certsUrl: string, kid: string): Promise<CryptoKey | undefined> {
  const now = Date.now();
  const fresh = cache && cache.url === certsUrl && now - cache.fetchedAt < CERT_TTL_MS;
  if (fresh && cache!.keys.has(kid)) return cache!.keys.get(kid);
  // Unknown kid (key rotation) or stale cache: refetch, but not more than once a minute.
  if (!cache || cache.url !== certsUrl || now - cache.fetchedAt >= CERT_REFRESH_MIN_INTERVAL_MS) {
    cache = { url: certsUrl, keys: await loadCerts(certsUrl), fetchedAt: now };
  }
  return cache.keys.get(kid);
}

export type AccessVerifyResult = { ok: true; email?: string } | { ok: false; reason: string };

export async function verifyAccessJwt(
  token: string,
  teamDomainRaw: string,
  aud: string,
): Promise<AccessVerifyResult> {
  const teamDomain = normalizeTeamDomain(teamDomainRaw);
  if (!teamDomain || !aud) return { ok: false, reason: "access not configured" };

  const parts = token.split(".");
  if (parts.length !== 3) return { ok: false, reason: "malformed token" };
  const [h, p, s] = parts as [string, string, string];

  let header: { alg?: string; kid?: string };
  let payload: { aud?: string | string[]; iss?: string; exp?: number; nbf?: number; email?: string };
  let signature: Uint8Array;
  try {
    header = JSON.parse(new TextDecoder().decode(base64UrlDecode(h)));
    payload = JSON.parse(new TextDecoder().decode(base64UrlDecode(p)));
    signature = base64UrlDecode(s);
  } catch {
    return { ok: false, reason: "malformed token" };
  }
  if (header.alg !== "RS256" || typeof header.kid !== "string") {
    return { ok: false, reason: "unsupported token header" };
  }

  let key: CryptoKey | undefined;
  try {
    key = await getKey(`https://${teamDomain}/cdn-cgi/access/certs`, header.kid);
  } catch {
    return { ok: false, reason: "cannot fetch signing keys" };
  }
  if (!key) return { ok: false, reason: "unknown signing key" };

  const valid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    signature,
    new TextEncoder().encode(`${h}.${p}`),
  );
  if (!valid) return { ok: false, reason: "bad signature" };

  const now = Math.floor(Date.now() / 1000);
  if (typeof payload.exp !== "number" || payload.exp + CLOCK_SKEW_S < now) {
    return { ok: false, reason: "token expired" };
  }
  if (typeof payload.nbf === "number" && payload.nbf - CLOCK_SKEW_S > now) {
    return { ok: false, reason: "token not yet valid" };
  }
  const auds = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  if (!auds.includes(aud)) return { ok: false, reason: "wrong audience" };
  if (payload.iss !== `https://${teamDomain}`) return { ok: false, reason: "wrong issuer" };

  return { ok: true, email: payload.email };
}

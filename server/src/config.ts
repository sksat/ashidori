// Server variables that set registration and resource limits.
//
// Fails closed: registration opens only for exactly REGISTRATION = "open".
// A numeric limit that is unset or empty takes its default; one that is set
// to anything but a positive integer (e.g. "0", "-1", "10MiB", " 5") is
// invalid. With an invalid limit the server refuses to create anything new
// (users, devices, batches, shared check-ins) until it is fixed, and the
// deploy workflow fails before deploying it (scripts/check-config.ts).
//
// This file has no imports, so that Node can run it directly in CI.

export type RegistrationMode = "admin" | "open";

/** Only exactly "open" opens registration; anything else, including "Open" or " open", means "admin". */
export function registrationMode(env: { REGISTRATION?: string }): RegistrationMode {
  return env.REGISTRATION === "open" ? "open" : "admin";
}

const GIB = 1024 * 1024 * 1024;

export interface Limits {
  /** Per user (or the legacy devices together): total blob bytes. */
  userQuotaBytes: number;
  /** Per user: number of batches. */
  userMaxBatches: number;
  /** Per user: number of devices ever registered, revoked ones included. */
  userMaxDevices: number;
  /** Per user: shared check-ins, tombstones included. */
  userMaxSharedCheckins: number;
  /** Whole server: users. Self-registration stops at this number; admins may still create users. */
  maxUsers: number;
  /** Whole server: total blob bytes. */
  maxTotalBytes: number;
}

export const LIMIT_VARS = {
  USER_QUOTA_BYTES: { key: "userQuotaBytes", default: 2 * GIB },
  USER_MAX_BATCHES: { key: "userMaxBatches", default: 200_000 },
  USER_MAX_DEVICES: { key: "userMaxDevices", default: 50 },
  USER_MAX_SHARED_CHECKINS: { key: "userMaxSharedCheckins", default: 5_000 },
  MAX_USERS: { key: "maxUsers", default: 100 },
  MAX_TOTAL_BYTES: { key: "maxTotalBytes", default: 50 * GIB },
} as const satisfies Record<string, { key: keyof Limits; default: number }>;

export type LimitVar = keyof typeof LIMIT_VARS;

export const DEFAULT_LIMITS: Limits = Object.fromEntries(
  Object.values(LIMIT_VARS).map((v) => [v.key, v.default]),
) as unknown as Limits;

/** A positive integer, `undefined` for unset/empty (= default), or null if invalid. */
export function parsePositiveInt(raw: string | undefined): number | undefined | null {
  if (raw === undefined || raw === "") return undefined;
  if (!/^[1-9][0-9]{0,15}$/.test(raw)) return null;
  const n = Number(raw);
  return Number.isSafeInteger(n) ? n : null;
}

export type LimitsResult = { ok: true; limits: Limits } | { ok: false; invalid: LimitVar[] };

export function parseLimits(env: Partial<Record<LimitVar, string>>): LimitsResult {
  const limits = { ...DEFAULT_LIMITS };
  const invalid: LimitVar[] = [];
  for (const [name, spec] of Object.entries(LIMIT_VARS) as Array<[LimitVar, (typeof LIMIT_VARS)[LimitVar]]>) {
    const n = parsePositiveInt(env[name]);
    if (n === null) invalid.push(name);
    else if (n !== undefined) limits[spec.key] = n;
  }
  return invalid.length > 0 ? { ok: false, invalid } : { ok: true, limits };
}

/** The variables scripts/check-config.ts checks. */
export type CheckedVar = LimitVar | "REGISTRATION" | "ANDROID_CERT_SHA256";

/**
 * Problems a deploy must not go out with (used by scripts/check-config.ts):
 * invalid limits, a REGISTRATION that is neither empty, "admin" nor "open"
 * (at runtime such a value means "admin", but it is most likely a typo), and
 * a malformed ANDROID_CERT_SHA256.
 */
export function configErrors(env: Partial<Record<CheckedVar, string>>): string[] {
  const errors: string[] = [];
  const reg = env.REGISTRATION ?? "";
  if (reg !== "" && reg !== "admin" && reg !== "open") {
    errors.push(`REGISTRATION must be "admin" or "open" (or empty for "admin"), got ${JSON.stringify(reg)}`);
  }
  const r = parseLimits(env);
  if (!r.ok) {
    for (const name of r.invalid) {
      errors.push(`${name} must be empty (default) or a positive integer, got ${JSON.stringify(env[name])}`);
    }
  }
  if (parseCertFingerprints(env.ANDROID_CERT_SHA256) === null) {
    errors.push(
      `ANDROID_CERT_SHA256 must be empty or comma-separated SHA-256 fingerprints (AB:CD:... or 64 hex digits), got ${JSON.stringify(env.ANDROID_CERT_SHA256)}`,
    );
  }
  return errors;
}

/**
 * ANDROID_CERT_SHA256: the SHA-256 fingerprints of the app's signing
 * certificates for /.well-known/assetlinks.json, comma-separated, each 32
 * colon-separated hex bytes as keytool prints them, or 64 hex digits as
 * apksigner prints them (case does not matter, spaces around commas are
 * ignored). Returns them in the colon form, uppercase, as assetlinks.json
 * wants them; [] for unset or empty; null if any entry is malformed.
 */
export function parseCertFingerprints(raw: string | undefined): string[] | null {
  if (raw === undefined || raw.trim() === "") return [];
  const out: string[] = [];
  for (const part of raw.split(",")) {
    let fp = part.trim().toUpperCase();
    if (/^[0-9A-F]{64}$/.test(fp)) fp = fp.match(/../g)!.join(":");
    if (!/^[0-9A-F]{2}(:[0-9A-F]{2}){31}$/.test(fp)) return null;
    if (!out.includes(fp)) out.push(fp);
  }
  return out;
}

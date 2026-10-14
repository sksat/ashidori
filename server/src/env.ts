export interface Env {
  DB: D1Database;
  BLOBS: R2Bucket;
  CF_ACCESS_TEAM_DOMAIN: string;
  CF_ACCESS_AUD: string;
  CORS_ALLOWED_ORIGINS: string;
  ALLOW_DEV_ADMIN: string;
  ADMIN_DEV_TOKEN?: string;
  /** Comma-separated, case-insensitive e-mail addresses of the admins. */
  ADMIN_EMAILS: string;
  /** Exactly "open": anyone may create an account. Anything else = "admin" (see config.ts). */
  REGISTRATION: string;
  // Limits (see config.ts): empty = default; any other non-positive-integer
  // value is invalid and stops the creation of new users, devices and data.
  USER_QUOTA_BYTES: string;
  USER_MAX_BATCHES: string;
  USER_MAX_DEVICES: string;
  USER_MAX_SHARED_CHECKINS: string;
  MAX_USERS: string;
  MAX_TOTAL_BYTES: string;
  /**
   * SHA-256 fingerprints of the app's signing certificates, comma-separated,
   * for /.well-known/assetlinks.json (App Links). Empty = none (see config.ts).
   */
  ANDROID_CERT_SHA256: string;
  // Workers Rate Limiting bindings (coarse, per Cloudflare location) checked
  // before the precise D1 counters; optional (see ratelimit.ts).
  RL_REGISTER?: RateLimit;
  RL_ACCOUNT?: RateLimit;
  RL_PAIRING?: RateLimit;
  RL_LOOKUP?: RateLimit;
}

export interface DeviceRow {
  id: string;
  name: string;
  token_hash: string;
  created_at: number;
  last_seen_at: number | null;
  revoked_at: number | null;
  /** NULL for devices registered before accounts existed. */
  user_id: string | null;
}

export type UserStatus = "active" | "disabled";

export interface UserRow {
  id: string;
  name: string;
  /** Lowercase; NULL for users that cannot be looked up. */
  handle: string | null;
  email: string | null;
  status: UserStatus;
  created_at: number;
  /** E-mail address of the admin who created the user, or "self" (self-registered, see accounts.ts). */
  created_by: string;
  disabled_at: number | null;
}

export type AppEnv = {
  Bindings: Env;
  Variables: {
    device: DeviceRow;
    /** E-mail address of the signed-in admin (/admin/* only). */
    adminEmail: string;
  };
};

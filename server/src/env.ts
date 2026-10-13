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
  /** "admin" (default) or "open" (anyone may create an account). Anything else = "admin". */
  REGISTRATION: string;
  /** Per-user storage quota in bytes. Empty or invalid = 2 GiB. */
  USER_QUOTA_BYTES: string;
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

export interface Env {
  DB: D1Database;
  BLOBS: R2Bucket;
  CF_ACCESS_TEAM_DOMAIN: string;
  CF_ACCESS_AUD: string;
  CORS_ALLOWED_ORIGINS: string;
  ALLOW_DEV_ADMIN: string;
  ADMIN_DEV_TOKEN?: string;
}

export interface DeviceRow {
  id: string;
  name: string;
  token_hash: string;
  created_at: number;
  last_seen_at: number | null;
  revoked_at: number | null;
}

export type AppEnv = {
  Bindings: Env;
  Variables: {
    device: DeviceRow;
  };
};

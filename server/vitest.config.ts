import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig(async () => {
  const migrations = await readD1Migrations("./migrations");
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          bindings: {
            TEST_MIGRATIONS: migrations,
            CF_ACCESS_TEAM_DOMAIN: "ashidori-test.cloudflareaccess.com",
            CF_ACCESS_AUD: "test-aud",
            ALLOW_DEV_ADMIN: "true",
            ADMIN_DEV_TOKEN: "dev-admin-token-for-tests",
            CORS_ALLOWED_ORIGINS: "https://viewer.example",
          },
        },
      }),
    ],
    test: {
      // The viewer (web/) has its own tests, run with its own config.
      include: ["test/**/*.test.ts"],
      setupFiles: ["./test/setup.ts"],
    },
  };
});

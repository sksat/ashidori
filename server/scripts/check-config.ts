// Validates the Worker variables a deploy passes (REGISTRATION, the numeric
// limits and ANDROID_CERT_SHA256, see src/config.ts) from the environment.
// Exits non-zero if any is invalid, so that CI never deploys a configuration
// the server would refuse at runtime. Run with Node 24 (type stripping):
// node scripts/check-config.ts
import { type CheckedVar, configErrors, LIMIT_VARS, type LimitVar } from "../src/config.ts";

const names: CheckedVar[] = ["REGISTRATION", "ANDROID_CERT_SHA256", ...(Object.keys(LIMIT_VARS) as LimitVar[])];
const env: Partial<Record<CheckedVar, string>> = {};
for (const name of names) {
  const v = process.env[name];
  if (v !== undefined) env[name] = v;
}
const errors = configErrors(env);
for (const e of errors) console.error(`::error::${e}`);
if (errors.length > 0) process.exit(1);
console.log("server variables are valid");

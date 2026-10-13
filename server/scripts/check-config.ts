// Validates the Worker variables a deploy passes (REGISTRATION and the
// numeric limits, see src/config.ts) from the environment. Exits non-zero if
// any is invalid, so that CI never deploys a configuration the server would
// refuse at runtime. Run with Node 24 (type stripping): node scripts/check-config.ts
import { configErrors, LIMIT_VARS, type LimitVar } from "../src/config.ts";

const names: Array<LimitVar | "REGISTRATION"> = ["REGISTRATION", ...(Object.keys(LIMIT_VARS) as LimitVar[])];
const env: Partial<Record<LimitVar | "REGISTRATION", string>> = {};
for (const name of names) {
  const v = process.env[name];
  if (v !== undefined) env[name] = v;
}
const errors = configErrors(env);
for (const e of errors) console.error(`::error::${e}`);
if (errors.length > 0) process.exit(1);
console.log("server variables are valid");

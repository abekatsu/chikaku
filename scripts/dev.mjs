#!/usr/bin/env node
/**
 * ローカル開発の一式を立ち上げる。
 *
 *   Access の代役 (8799) ── JWKS を配る
 *   wrangler dev  (8787) ── Worker + ローカル D1
 *   vite          (5173) ── ダッシュボード。API を 8787 へ転送し、
 *                           その際に検証可能な JWT を付ける
 *
 * 本番では Cloudflare Access が JWT を付けるが、ローカルには Access が
 * 居ない。検証を無効化する抜け道を Worker 側に作ると本番に混入しかねないので、
 * **代わりに検証を通る本物の JWT を用意する**方式にしている。
 */
import { spawn, execFile } from "node:child_process";
import { promisify } from "node:util";

import { startAccessStub } from "./access-stub.mjs";

const execFileAsync = promisify(execFile);

const JWKS_PORT = 8799;
const WORKER_PORT = 8787;
const CONFIG = "server/wrangler.test.jsonc";
const PERSIST = ".wrangler/dev-state";

// 開発用の家族と子アカウント。ID を固定して、再起動しても同じデータを使う。
const DEV_FAMILY_ID = "00000000-0000-4000-8000-000000000001";
const DEV_CHILD_ID = "00000000-0000-4000-8000-000000000002";
const DEV_EMAIL = "dev@example.com";

const children = [];

function run(command, args, options = {}) {
  const child = spawn(command, args, { stdio: "inherit", ...options });
  children.push(child);
  return child;
}

async function sql(statement) {
  await execFileAsync("npx", [
    "wrangler", "d1", "execute", "chikaku-test", "--local",
    "-c", CONFIG, "--persist-to", PERSIST, "--command", statement,
  ]);
}

function shutdown() {
  for (const child of children) child.kill("SIGTERM");
}

async function main() {
  const stub = await startAccessStub({ port: JWKS_PORT });

  console.log("マイグレーションを適用しています…");
  await execFileAsync("npx", [
    "wrangler", "d1", "migrations", "apply", "chikaku-test",
    "--local", "-c", CONFIG, "--persist-to", PERSIST,
  ]);

  // 何度実行しても増えないよう OR IGNORE で入れる。
  await sql(
    `INSERT OR IGNORE INTO families (id, name, created_at) ` +
    `VALUES ('${DEV_FAMILY_ID}', '開発用の家族', ${Date.now()});` +
    `INSERT OR IGNORE INTO children_accounts (id, family_id, email, display_name, created_at) ` +
    `VALUES ('${DEV_CHILD_ID}', '${DEV_FAMILY_ID}', '${DEV_EMAIL}', '開発ユーザー', ${Date.now()});`,
  );

  // 作業中に切れないよう長めに取る。
  const jwt = await stub.mintJwt({ email: DEV_EMAIL, expiresIn: "12h" });

  run("npx", [
    "wrangler", "dev", "-c", CONFIG, "--local",
    "--port", String(WORKER_PORT), "--persist-to", PERSIST, "--log-level", "warn",
  ]);

  run("npm", ["run", "dev"], {
    cwd: "client",
    env: {
      ...process.env,
      CHIKAKU_DEV_JWT: jwt,
      CHIKAKU_WORKER_ORIGIN: `http://127.0.0.1:${WORKER_PORT}`,
    },
  });

  console.log(`
────────────────────────────────────────────
  ダッシュボード  http://localhost:5173
  Worker          http://127.0.0.1:${WORKER_PORT}
  Access の代役   ${stub.issuer}
  ログイン中      ${DEV_EMAIL}（開発用・自動でサインイン済み）
────────────────────────────────────────────
`);

  process.on("SIGINT", () => {
    shutdown();
    stub.close();
    process.exit(0);
  });
  process.on("SIGTERM", () => {
    shutdown();
    stub.close();
    process.exit(0);
  });
}

main().catch((e) => {
  console.error(e);
  shutdown();
  process.exit(1);
});

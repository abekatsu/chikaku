#!/usr/bin/env node
/**
 * iOS アプリの通信層を、ローカルで動かした Worker に対して実際に叩く。
 *
 * Android 側は `ApiClient.kt` の契約を server/tests/e2e.mjs が固定しているが、
 * iOS 側にも同じ保証が要る。**サーバーのステータスコードを変えると
 * アプリの再送ロジックが変わる**ため、その関係をここで機械的に確かめる。
 *
 * UI は経由しない。`Chikaku/Data/ApiClient.swift` などアプリ本体のソースを
 * そのままコンパイルして叩くので、テスト用の複製を持たない。
 *
 *   npm run test:ios
 */
import { spawn, execFile } from "node:child_process";
import { rm } from "node:fs/promises";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve as resolvePath } from "node:path";

const execFileAsync = promisify(execFile);

const here = dirname(fileURLToPath(import.meta.url));
const iosRoot = resolvePath(here, "..", "..");
const repoRoot = resolvePath(iosRoot, "..");

const PORT = 8791;
const CONFIG = "server/wrangler.test.jsonc";
const PERSIST = ".wrangler/ios-contract-state";
const FAMILY = "00000000-0000-4000-8000-00000000f001";
const CHILD = "00000000-0000-4000-8000-00000000f002";
// 招待コードは見間違えやすい 0 1 2 B I L O S Z を除いた27文字から作る規約。
const CODE = "K7QM4XDF";

const BINARY = "/tmp/chikaku-contract-check";

// アプリ本体のうち、通信に関わる部分だけを取り出してコンパイルする。
// UIKit / CoreLocation に触る型は入れない（macOS でビルドできなくなる）。
const SOURCES = [
  "Chikaku/Config.swift",
  "Chikaku/Data/ApiModels.swift",
  "Chikaku/Data/ApiClient.swift",
  "Chikaku/Data/Timestamp.swift",
  "Tools/contract-check/main.swift",
];

const children = [];

function shutdown() {
  for (const child of children) {
    try { child.kill("SIGTERM"); } catch { /* 既に終了 */ }
  }
}

function sql(statement) {
  return execFileAsync("npx", [
    "wrangler", "d1", "execute", "chikaku-test", "--local",
    "-c", CONFIG, "--persist-to", PERSIST, "--command", statement,
  ], { cwd: repoRoot });
}

async function waitForWorker() {
  for (let i = 0; i < 60; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/api/v1/healthz`);
      if (res.ok) return;
    } catch { /* まだ起動していない */ }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error("wrangler dev が起動しませんでした");
}

async function main() {
  // `-D DEBUG` を付けるのは、アプリの Debug 構成に合わせるため。
  // これが無いと ApiClient が https 以外を拒否し、ローカルの Worker に届かない
  // （TLS 必須は CLAUDE.md §5。リリースビルドではこの拒否が正しい挙動）。
  console.log("通信層をコンパイルしています…");
  await execFileAsync("swiftc", [
    "-swift-version", "6", "-D", "DEBUG", "-o", BINARY, ...SOURCES,
  ], { cwd: iosRoot });

  // 毎回まっさらな DB から始める。
  await rm(join(repoRoot, PERSIST), { recursive: true, force: true });

  console.log("マイグレーションを適用しています…");
  await execFileAsync("npx", [
    "wrangler", "d1", "migrations", "apply", "chikaku-test",
    "--local", "-c", CONFIG, "--persist-to", PERSIST,
  ], { cwd: repoRoot });

  console.log("wrangler dev を起動しています…");
  const worker = spawn("npx", [
    "wrangler", "dev", "-c", CONFIG, "--local", "--port", String(PORT),
    "--persist-to", PERSIST, "--log-level", "warn",
  ], { cwd: repoRoot, stdio: "ignore" });
  children.push(worker);
  process.on("exit", shutdown);

  await waitForWorker();

  const now = Date.now();
  await sql(
    `INSERT OR IGNORE INTO families (id, name, created_at) ` +
    `VALUES ('${FAMILY}', 'contract', ${now})`,
  );
  await sql(
    `INSERT OR IGNORE INTO children_accounts (id, family_id, email, display_name, created_at) ` +
    `VALUES ('${CHILD}', '${FAMILY}', 'ios@example.com', '契約確認', ${now})`,
  );
  await sql(
    `INSERT OR REPLACE INTO invite_codes ` +
    `(code, family_id, created_by, created_at, expires_at, used_at) ` +
    `VALUES ('${CODE}', '${FAMILY}', '${CHILD}', ${now}, ${now + 86_400_000}, NULL)`,
  );

  console.log("");
  const check = spawn(BINARY, [`http://127.0.0.1:${PORT}`, CODE], { stdio: "inherit" });
  const code = await new Promise((r) => check.on("exit", r));

  shutdown();
  process.exit(code ?? 1);
}

main().catch((error) => {
  console.error(error);
  shutdown();
  process.exit(1);
});

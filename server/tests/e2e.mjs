#!/usr/bin/env node
/**
 * Worker の e2e テスト。
 *
 * Android 側 (`ApiClient.kt`) が前提にしている契約 ―― ステータスコードの意味と
 * JSON の形 ―― をここで固定する。Axum 版の `tests/api.rs` の後継。
 *
 * Cloudflare Access の検証は迂回しない。テスト用の RSA 鍵で JWKS を配る
 * ローカルサーバーを立て、Worker にはそこを向かせる。したがって
 * 署名検証・aud・iss・exp の判定は本番と同じ経路を通る。
 */
import { spawn, execFile } from "node:child_process";
import { createServer } from "node:http";
import { rm } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { promisify } from "node:util";
import { generateKeyPair, exportJWK, SignJWT } from "jose";

const execFileAsync = promisify(execFile);

const WORKER_PORT = 8798;
const JWKS_PORT = 8799;
const ISSUER = `http://127.0.0.1:${JWKS_PORT}`;
const AUDIENCE = "test-aud-tag";
const CONFIG = "server/wrangler.test.jsonc";
const PERSIST = ".wrangler/test-state";
const BASE = `http://127.0.0.1:${WORKER_PORT}/api/v1`;

// ---------------------------------------------------------------- 最小のテストランナー

const tests = [];
let passed = 0;
const failures = [];

function test(name, fn) {
  tests.push({ name, fn });
}

function assert(cond, message) {
  if (!cond) throw new Error(message);
}

function assertEqual(actual, expected, message = "") {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}\n  期待: ${JSON.stringify(expected)}\n  実際: ${JSON.stringify(actual)}`);
  }
}

// ---------------------------------------------------------------- 認証情報の生成

let signingKey;
let jwks;

async function setupKeys() {
  const { publicKey, privateKey } = await generateKeyPair("RS256", { extractable: true });
  const jwk = await exportJWK(publicKey);
  jwk.kid = "test-kid";
  jwk.alg = "RS256";
  jwk.use = "sig";
  jwks = { keys: [jwk] };
  signingKey = privateKey;
}

async function mintJwt({
  email = "child-a@example.com",
  audience = AUDIENCE,
  issuer = ISSUER,
  expiresIn = "1h",
  extra = {},
  kid = "test-kid",
} = {}) {
  return new SignJWT({ email, type: "app", ...extra })
    .setProtectedHeader({ alg: "RS256", kid })
    .setIssuer(issuer)
    .setAudience(audience)
    .setIssuedAt()
    .setExpirationTime(expiresIn)
    .sign(signingKey);
}

// ---------------------------------------------------------------- HTTP 呼び出し

async function api(method, path, { jwt, bearer, body } = {}) {
  const headers = {};
  if (jwt) headers["Cf-Access-Jwt-Assertion"] = jwt;
  if (bearer) headers["Authorization"] = `Bearer ${bearer}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = { raw: text };
  }
  return { status: res.status, body: json };
}

// ---------------------------------------------------------------- 下ごしらえ

async function sql(statement) {
  await execFileAsync(
    "npx",
    ["wrangler", "d1", "execute", "chikaku-test", "--local", "-c", CONFIG,
     "--persist-to", PERSIST, "--command", statement],
    { cwd: process.cwd() },
  );
}

/** 家族と子アカウントを作る。Access が認証を担うのでパスワードは無い。 */
async function seedFamily(email) {
  const familyId = randomUUID();
  const childId = randomUUID();
  const now = Date.now();
  await sql(
    `INSERT INTO families (id, name, created_at) VALUES ('${familyId}', 'テスト家族', ${now});` +
    `INSERT INTO children_accounts (id, family_id, email, display_name, created_at) ` +
    `VALUES ('${childId}', '${familyId}', '${email}', '子', ${now});`,
  );
  return { familyId, childId, jwt: await mintJwt({ email }) };
}

/** 招待コードを発行して端末を登録する。 */
async function pairDevice(familyId, jwt) {
  const invite = await api("POST", `/families/${familyId}/invites`, { jwt });
  assertEqual(invite.status, 201, "招待コードの発行に失敗");
  const reg = await api("POST", "/devices/register", {
    body: { invite_code: invite.body.code, device_name: "お父さんのスマホ", device_model: "Pixel 9" },
  });
  assertEqual(reg.status, 200, `端末登録に失敗: ${JSON.stringify(reg.body)}`);
  return { deviceId: reg.body.device_id, deviceToken: reg.body.device_token };
}

const iso = (ms) => new Date(ms).toISOString();
const fix = (deviceId, at, extra = {}) => ({
  device_id: deviceId,
  lat: 35.6812,
  lng: 139.7671,
  accuracy: 12.5,
  timestamp: at,
  battery_level: 77,
  ...extra,
});

// ================================================================ ペアリング

test("ペアリングが成立し、端末トークンが 32 バイト hex で返る", async () => {
  const family = await seedFamily("pair@example.com");
  const { deviceId, deviceToken } = await pairDevice(family.familyId, family.jwt);
  assert(deviceId.length > 0, "device_id が空");
  assertEqual(deviceToken.length, 64, "device_token の長さが違う");
});

test("招待コードは一度しか使えない", async () => {
  const family = await seedFamily("once@example.com");
  const invite = await api("POST", `/families/${family.familyId}/invites`, { jwt: family.jwt });
  const payload = { invite_code: invite.body.code, device_name: "端末", device_model: "X" };

  assertEqual((await api("POST", "/devices/register", { body: payload })).status, 200);

  // 2 回目は 401。アプリはこれを受けて「招待コードが正しくないか、
  // 期限が切れています」と表示する (MainViewModel の Unauthorized 分岐)。
  const second = await api("POST", "/devices/register", { body: payload });
  assertEqual(second.status, 401, "使用済みコードが通ってしまった");
  assertEqual(second.body.error, "invalid_invite_code");
});

test("招待コードは小文字・ハイフン混じりでも通る", async () => {
  const family = await seedFamily("lower@example.com");
  const invite = await api("POST", `/families/${family.familyId}/invites`, { jwt: family.jwt });
  const code = invite.body.code.toLowerCase();
  const typed = `${code.slice(0, 4)}-${code.slice(4)}`;

  const res = await api("POST", "/devices/register", {
    body: { invite_code: typed, device_name: "端末", device_model: "X" },
  });
  assertEqual(res.status, 200, `正規化されていない: ${JSON.stringify(res.body)}`);
});

test("存在しない招待コードは 401", async () => {
  const res = await api("POST", "/devices/register", {
    body: { invite_code: "XXXXXXXX", device_name: "端末", device_model: "X" },
  });
  assertEqual(res.status, 401);
  assertEqual(res.body.error, "invalid_invite_code");
});

test("端末名は必須で、message は日本語で返る", async () => {
  const family = await seedFamily("noname@example.com");
  const invite = await api("POST", `/families/${family.familyId}/invites`, { jwt: family.jwt });
  const res = await api("POST", "/devices/register", {
    body: { invite_code: invite.body.code, device_name: "   ", device_model: "X" },
  });
  // アプリはこの message をそのまま高齢の利用者の画面に出す。
  assertEqual(res.status, 400);
  assert(res.body.message.includes("名前"), `文言が想定外: ${res.body.message}`);
});

// ================================================================ 位置情報送信

test("位置情報が保存され latest に出る", async () => {
  const family = await seedFamily("loc@example.com");
  const { deviceId, deviceToken } = await pairDevice(family.familyId, family.jwt);
  const at = iso(Date.now());

  const post = await api("POST", "/location", { bearer: deviceToken, body: fix(deviceId, at) });
  assertEqual(post.status, 202, `受理されなかった: ${JSON.stringify(post.body)}`);
  assertEqual(post.body.stored, true);

  const latest = await api("GET", `/families/${family.familyId}/latest`, { jwt: family.jwt });
  assertEqual(latest.status, 200);
  const device = latest.body.devices[0];
  assertEqual(device.device_id, deviceId);
  assertEqual(device.device_name, "お父さんのスマホ");
  assertEqual(device.latest.lat, 35.6812);
  assertEqual(device.latest.battery_level, 77);
  assertEqual(device.latest.recorded_at, at, "時刻が往復で変わっている");
});

test("同じ測位の再送は重複しない", async () => {
  const family = await seedFamily("dup@example.com");
  const { deviceId, deviceToken } = await pairDevice(family.familyId, family.jwt);
  const at = iso(Date.now());
  const payload = fix(deviceId, at);

  const first = await api("POST", "/location", { bearer: deviceToken, body: payload });
  assertEqual(first.body.stored, true);

  // 応答が失われたあとの WorkManager 再送を模す。
  const second = await api("POST", "/location", { bearer: deviceToken, body: payload });
  assertEqual(second.status, 202);
  assertEqual(second.body.stored, false, "重複が畳まれていない");

  const history = await api("GET", `/families/${family.familyId}/history`, { jwt: family.jwt });
  assertEqual(history.body.events.length, 1, "履歴が 2 件になっている");
});

test("他端末になりすました投稿は 401", async () => {
  const family = await seedFamily("spoof@example.com");
  const { deviceToken } = await pairDevice(family.familyId, family.jwt);
  const res = await api("POST", "/location", {
    bearer: deviceToken,
    body: fix("someone-else", iso(Date.now())),
  });
  assertEqual(res.status, 401);
});

test("トークンが無い・偽物なら 401", async () => {
  for (const bearer of [undefined, "not-a-real-token"]) {
    const res = await api("POST", "/location", { bearer, body: fix("d", iso(Date.now())) });
    assertEqual(res.status, 401, `token=${bearer}`);
  }
});

test("壊れたペイロードは 400 で、再試行されずに捨てられる", async () => {
  const family = await seedFamily("bad@example.com");
  const { deviceId, deviceToken } = await pairDevice(family.familyId, family.jwt);
  const now = Date.now();
  const day = 86_400_000;

  const cases = [
    ["壊れた時刻", fix(deviceId, "きのう")],
    ["未来すぎる時刻", fix(deviceId, iso(now + 60 * day))],
    ["保持期間より古い", fix(deviceId, iso(now - 200 * day))],
    ["緯度が範囲外", fix(deviceId, iso(now), { lat: 91 })],
    ["経度が範囲外", fix(deviceId, iso(now), { lng: 181 })],
    ["電池残量が範囲外", fix(deviceId, iso(now), { battery_level: 101 })],
  ];

  for (const [name, body] of cases) {
    const res = await api("POST", "/location", { bearer: deviceToken, body });
    // 4xx かつ 401/408/429 以外 ―― アプリはこれを ClientError として
    // 扱い、再試行を打ち切ってキューから捨てる。
    assertEqual(res.status, 400, name);
    assert(typeof res.body.message === "string", `${name}: message が無い`);
  }
});

test("電池残量 -1 は受け付ける", async () => {
  const family = await seedFamily("batt@example.com");
  const { deviceId, deviceToken } = await pairDevice(family.familyId, family.jwt);
  // 端末は電池残量を取れないとき -1 を送る (LocationRepository)。
  const res = await api("POST", "/location", {
    bearer: deviceToken,
    body: fix(deviceId, iso(Date.now()), { battery_level: -1 }),
  });
  assertEqual(res.status, 202, JSON.stringify(res.body));
});

test("無効化した端末は送信できなくなる", async () => {
  const family = await seedFamily("revoke@example.com");
  const { deviceId, deviceToken } = await pairDevice(family.familyId, family.jwt);

  const revoked = await api("POST", `/families/${family.familyId}/devices/${deviceId}/revoke`, {
    jwt: family.jwt,
  });
  assertEqual(revoked.status, 204);

  const res = await api("POST", "/location", { bearer: deviceToken, body: fix(deviceId, iso(Date.now())) });
  assertEqual(res.status, 401, "無効化が効いていない");
});

// ================================================================ ダッシュボード

test("履歴は期間で絞られ、昇順で返る", async () => {
  const family = await seedFamily("hist@example.com");
  const { deviceId, deviceToken } = await pairDevice(family.familyId, family.jwt);
  const now = Date.now();

  for (const minutesAgo of [90, 30, 5]) {
    const res = await api("POST", "/location", {
      bearer: deviceToken,
      body: fix(deviceId, iso(now - minutesAgo * 60_000)),
    });
    assertEqual(res.status, 202);
  }

  const from = iso(now - 60 * 60_000);
  const res = await api("GET", `/families/${family.familyId}/history?from=${encodeURIComponent(from)}`, {
    jwt: family.jwt,
  });
  assertEqual(res.status, 200);
  // 90 分前の 1 件だけが範囲外。
  assertEqual(res.body.events.length, 2, JSON.stringify(res.body));
  assertEqual(res.body.truncated, false);
  assert(res.body.events[0].recorded_at < res.body.events[1].recorded_at, "昇順でない");
});

test("履歴の時刻指定が壊れていれば 400", async () => {
  const family = await seedFamily("histbad@example.com");
  const res = await api("GET", `/families/${family.familyId}/history?from=yesterday`, { jwt: family.jwt });
  assertEqual(res.status, 400, JSON.stringify(res.body));
});

test("履歴の上限に達したら truncated が立つ", async () => {
  const family = await seedFamily("trunc@example.com");
  const { deviceId, deviceToken } = await pairDevice(family.familyId, family.jwt);
  const now = Date.now();

  for (let i = 0; i < 3; i++) {
    await api("POST", "/location", { bearer: deviceToken, body: fix(deviceId, iso(now - i * 60_000)) });
  }

  const res = await api("GET", `/families/${family.familyId}/history?limit=2`, { jwt: family.jwt });
  assertEqual(res.status, 200);
  assertEqual(res.body.events.length, 2);
  assertEqual(res.body.truncated, true);
});

test("/me は本人と家族を返す", async () => {
  const family = await seedFamily("me@example.com");
  const res = await api("GET", "/me", { jwt: family.jwt });
  assertEqual(res.status, 200);
  assertEqual(res.body.family_id, family.familyId);
  assertEqual(res.body.email, "me@example.com");
});

// ================================================================ 家族の分離

test("他家族のデータには到達できない（403 ではなく 404）", async () => {
  const a = await seedFamily("famA@example.com");
  const b = await seedFamily("famB@example.com");
  const { deviceId, deviceToken } = await pairDevice(a.familyId, a.jwt);
  await api("POST", "/location", { bearer: deviceToken, body: fix(deviceId, iso(Date.now())) });

  // B の資格情報で A の family_id を指す。
  // 「存在するが権限が無い」ことすら漏らさないよう 404 を返す。
  for (const path of [`/families/${a.familyId}/latest`, `/families/${a.familyId}/history`]) {
    assertEqual((await api("GET", path, { jwt: b.jwt })).status, 404, path);
  }
  assertEqual((await api("POST", `/families/${a.familyId}/invites`, { jwt: b.jwt })).status, 404);
  assertEqual(
    (await api("POST", `/families/${a.familyId}/devices/${deviceId}/revoke`, { jwt: b.jwt })).status,
    404,
  );

  // B は自分の家族なら見られる（端末はまだ 0 台）。
  const own = await api("GET", `/families/${b.familyId}/latest`, { jwt: b.jwt });
  assertEqual(own.status, 200);
  assertEqual(own.body.devices.length, 0);
});

test("端末トークンではダッシュボード API を叩けない", async () => {
  const family = await seedFamily("crossuse@example.com");
  const { deviceToken } = await pairDevice(family.familyId, family.jwt);
  const res = await api("GET", `/families/${family.familyId}/latest`, { bearer: deviceToken });
  assertEqual(res.status, 401);
});

// ================================================================ Access の検証

test("Access JWT が無ければ 401", async () => {
  const family = await seedFamily("nojwt@example.com");
  assertEqual((await api("GET", `/families/${family.familyId}/latest`)).status, 401);
});

test("署名を改ざんした JWT は 401", async () => {
  const family = await seedFamily("tamper@example.com");
  const [h, p, s] = family.jwt.split(".");
  const broken = [h, p, (s[0] === "A" ? "B" : "A") + s.slice(1)].join(".");
  assertEqual((await api("GET", "/me", { jwt: broken })).status, 401);
});

test("クレームだけ差し替えた JWT は 401", async () => {
  await seedFamily("victim@example.com");
  const attacker = await seedFamily("attacker@example.com");
  const [h, , s] = attacker.jwt.split(".");
  // 署名はそのままに payload の email だけ他人に差し替える。
  const forged = Buffer.from(
    JSON.stringify({ email: "victim@example.com", aud: AUDIENCE, iss: ISSUER, exp: Math.floor(Date.now() / 1000) + 600 }),
  ).toString("base64url");
  assertEqual((await api("GET", "/me", { jwt: [h, forged, s].join(".") })).status, 401);
});

test("期限切れの JWT は 401", async () => {
  await seedFamily("expired@example.com");
  const jwt = await mintJwt({ email: "expired@example.com", expiresIn: "-1h" });
  assertEqual((await api("GET", "/me", { jwt })).status, 401);
});

test("aud が違う JWT は 401", async () => {
  await seedFamily("aud@example.com");
  const jwt = await mintJwt({ email: "aud@example.com", audience: "someone-elses-app" });
  assertEqual((await api("GET", "/me", { jwt })).status, 401);
});

test("iss が違う JWT は 401", async () => {
  await seedFamily("iss@example.com");
  const jwt = await mintJwt({ email: "iss@example.com", issuer: "https://evil.example.com" });
  assertEqual((await api("GET", "/me", { jwt })).status, 401);
});

test("知らない kid の JWT は 401", async () => {
  await seedFamily("kid@example.com");
  const jwt = await mintJwt({ email: "kid@example.com", kid: "unknown-kid" });
  assertEqual((await api("GET", "/me", { jwt })).status, 401);
});

test("サービストークンの JWT は 401（人ではないため）", async () => {
  await seedFamily("svc@example.com");
  const jwt = await mintJwt({ email: "svc@example.com", extra: { common_name: "some-client-id" } });
  assertEqual((await api("GET", "/me", { jwt })).status, 401);
});

test("Access は通るが未登録のメールは 403", async () => {
  // Access のポリシーとこの表の二重で絞る (ADR-3)。
  const jwt = await mintJwt({ email: "stranger@example.com" });
  const res = await api("GET", "/me", { jwt });
  assertEqual(res.status, 403);
  assertEqual(res.body.error, "not_provisioned");
});

// ================================================================ その他

test("未知のパスも JSON でエラーを返す", async () => {
  const res = await api("GET", "/nope");
  assertEqual(res.status, 404);
  // アプリのエラー解釈は常に {error, message} を前提にしている。
  assert(typeof res.body.error === "string", JSON.stringify(res.body));
  assert(typeof res.body.message === "string", JSON.stringify(res.body));
});

test("healthz は認証不要", async () => {
  const res = await api("GET", "/healthz");
  assertEqual(res.status, 200);
  assertEqual(res.body.status, "ok");
});

// ================================================================ 実行

async function startJwksServer() {
  const server = createServer((req, res) => {
    if (req.url === "/cdn-cgi/access/certs") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(jwks));
    } else {
      res.writeHead(404).end();
    }
  });
  await new Promise((resolve) => server.listen(JWKS_PORT, "127.0.0.1", resolve));
  return server;
}

async function waitForWorker(timeoutMs = 180_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${BASE}/healthz`);
      if (res.ok) return;
    } catch {
      /* まだ起動していない */
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error("wrangler dev が起動しませんでした");
}

async function main() {
  await setupKeys();
  // 毎回まっさらな DB から始める。
  await rm(PERSIST, { recursive: true, force: true });

  const jwksServer = await startJwksServer();
  console.log(`JWKS サーバー: ${ISSUER}`);

  console.log("マイグレーションを適用しています…");
  await execFileAsync("npx", [
    "wrangler", "d1", "migrations", "apply", "chikaku-test",
    "--local", "-c", CONFIG, "--persist-to", PERSIST,
  ]);

  console.log("wrangler dev を起動しています…");
  const worker = spawn(
    "npx",
    ["wrangler", "dev", "-c", CONFIG, "--local", "--port", String(WORKER_PORT),
     "--persist-to", PERSIST, "--log-level", "warn"],
    { stdio: ["ignore", "pipe", "pipe"] },
  );
  const workerLog = [];
  worker.stdout.on("data", (d) => workerLog.push(d.toString()));
  worker.stderr.on("data", (d) => workerLog.push(d.toString()));

  const cleanup = () => {
    worker.kill("SIGTERM");
    jwksServer.close();
  };
  process.on("exit", cleanup);

  try {
    await waitForWorker();
  } catch (e) {
    console.error(workerLog.join(""));
    cleanup();
    throw e;
  }

  console.log(`\n${tests.length} 件のテストを実行します\n`);
  for (const { name, fn } of tests) {
    try {
      await fn();
      passed++;
      console.log(`  ✓ ${name}`);
    } catch (e) {
      failures.push({ name, error: e });
      console.log(`  ✗ ${name}`);
    }
  }

  cleanup();

  console.log(`\n結果: ${passed} 件成功 / ${failures.length} 件失敗`);
  if (failures.length > 0) {
    console.log("");
    for (const { name, error } of failures) {
      console.log(`✗ ${name}\n  ${error.message}\n`);
    }
    process.exitCode = 1;
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});

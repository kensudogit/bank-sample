// HTTP の振る舞いのテスト：偽の勘定系 API を立て、取込・冪等性（再送の再現）・再試行・行員の署名・mTLS・指標を確かめる。
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { createServer as createTlsServer, request as httpsRequest } from "node:https";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createApp } from "../src/server.mjs";
import { createCoreClient } from "../src/core.mjs";
import { signOperatorToken } from "../src/operator.mjs";
import { createMetrics } from "../src/telemetry.mjs";

const fixture = (name) => readFileSync(new URL(`../../mulesoft/bank-integration/src/test/resources/fixtures/${name}`, import.meta.url));
const sha = (buf) => createHash("sha256").update(buf).digest("hex");
const OPERATOR_SECRET = "operator-secret-0123456789abcdefghijklmn";

// 偽の勘定系：ログイン・セッション切れ・障害の再現と、実行履歴（冪等キー）の保存
const fake = { calls: [], session: "s1", forceExpire: false, failWith: null, failTimes: 0, loginOk: true, runs: new Map(), forceInProgress: false };
let coreServer, appServer, coreUrl, appUrl, metrics;

function startServer(handler) {
  return new Promise((resolve) => {
    const s = createServer(handler);
    s.listen(0, "127.0.0.1", () => resolve(s));
  });
}
const json = (res, status, body, headers = {}) => {
  res.writeHead(status, { "Content-Type": "application/json", ...headers });
  res.end(JSON.stringify(body));
};

function runStart(b) {
  const key = `${b.consumer}|${b.kind}|${b.idempotencyKey}`;
  const r = fake.runs.get(key);
  if (!r) {
    const run = { runId: `run-${fake.runs.size + 1}`, ...b, status: "PROCESSING", attempts: 1, startedAt: new Date().toISOString() };
    fake.runs.set(key, run);
    return { state: fake.forceInProgress ? "IN_PROGRESS" : "NEW", runId: run.runId, attempts: 1 };
  }
  if (r.payloadSha256 !== b.payloadSha256) return { error: { code: "IDEMPOTENCY_KEY_REUSED", message: "同じキーで別の内容" } };
  if (r.status === "COMPLETED") return { state: "REPLAY", runId: r.runId, httpStatus: r.httpStatus, response: r.responseBody };
  if (r.status === "PROCESSING") return { state: "IN_PROGRESS", runId: r.runId };
  r.status = "PROCESSING";
  r.attempts++;
  return { state: "RETRY", runId: r.runId, attempts: r.attempts };
}

before(async () => {
  coreServer = await startServer(async (req, res) => {
    let body = "";
    for await (const c of req) body += c;
    const path = req.url.replace("/bank/api", "");
    fake.calls.push({ path, body: body ? JSON.parse(body) : null, headers: req.headers });
    if (path === "/health") return json(res, 200, { data: { status: "UP" } });
    if (path === "/login") {
      if (!fake.loginOk) return json(res, 401, { error: { code: "AUTH_FAILED", message: "違います" } });
      fake.session = `s${fake.calls.length}`;
      return json(res, 200, { data: { userId: "svc-mule", role: "INTEGRATION" } }, { "Set-Cookie": `JSESSIONID=${fake.session}; Path=/; HttpOnly` });
    }
    if (fake.forceExpire || req.headers.cookie !== `JSESSIONID=${fake.session}`) {
      fake.forceExpire = false;
      return json(res, 401, { error: { code: "UNAUTHENTICATED", message: "ログインしてください" } });
    }
    const b = body ? JSON.parse(body) : null;
    if (path === "/integration-run-start") {
      const r = runStart(b);
      return r.error ? json(res, 422, r) : json(res, 200, { data: r });
    }
    if (path === "/integration-run-finish") {
      const run = [...fake.runs.values()].find((r) => r.runId === b.runId);
      Object.assign(run, { status: b.status, outcome: b.outcome, summary: b.summary, httpStatus: Number(b.httpStatus), responseBody: b.responseBody });
      return json(res, 200, { data: run });
    }
    if (path.startsWith("/integration-runs")) {
      return json(res, 200, { data: [...fake.runs.values()].reverse().map((r) => ({ ...r, requestId: r.correlationId })) });
    }
    if (fake.failWith && fake.failTimes > 0) {
      fake.failTimes--;
      return json(res, fake.failWith, { error: { code: "X", message: "勘定系のエラー" } });
    }
    if (path === "/remit-incoming") {
      return json(res, 200, { data: b.items.map((it) => ({ status: it.nameKana === "ｻﾄｳ ﾊﾅｺ" ? "CREDITED" : "HELD", note: it.nameKana === "ｻﾄｳ ﾊﾅｺ" ? null : "受取人名不一致" })) });
    }
    if (path === "/billing-import") return json(res, 200, { data: b.items.map((it) => ({ ...it, result: it.billerCode === "ELEC01" ? "取込" : "契約なし" })) });
    if (path === "/fx-rate-update") return json(res, 200, { data: [] });
    return json(res, 404, { error: { code: "NOT_FOUND", message: "なし" } });
  });
  coreUrl = `http://127.0.0.1:${coreServer.address().port}/bank/api`;
  metrics = createMetrics();
  const core = createCoreClient({
    baseUrl: coreUrl, user: "svc-mule", password: "Test-Pass-2026", backoffMs: 5,
    onCall: ({ path, result }) => metrics.inc("mule_core_calls_total", { path, result }),
  });
  const app = createApp({ core, log: () => undefined, operatorSecret: OPERATOR_SECRET, metrics });
  appServer = await startServer(app);
  appUrl = `http://127.0.0.1:${appServer.address().port}`;
  appServer.admin = await startServer(app.admin);
});

after(() => {
  coreServer.close();
  appServer.close();
  appServer.admin.close();
});

const post = (path, body, contentType, headers = {}) =>
  fetch(`${appUrl}${path}`, { method: "POST", body, headers: { "Content-Type": contentType, ...headers } });
const businessCalls = () => fake.calls.map((c) => c.path).filter((p) => !p.startsWith("/integration-run") && p !== "/login");

test("全銀データを取り込み、実行を登録・完了する。同じファイルの再送は勘定系を呼ばずに前回の応答を返す", async () => {
  fake.calls.length = 0;
  const res = await post("/api/v1/zengin/incoming", fixture("zengin-incoming-sample.txt"), "text/plain; charset=utf-8",
    { "X-Request-ID": "req-1", "X-Consumer-Username": "zengin-network" });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("x-request-id"), "req-1");
  const body = await res.json();
  assert.deepEqual(body.summary, { credited: 1, held: 1, duplicate: 0, rejected: 0 });
  assert.deepEqual(fake.calls.map((c) => c.path), ["/login", "/integration-run-start", "/remit-incoming", "/integration-run-finish"]);
  const start = fake.calls[1].body;
  assert.equal(start.consumer, "zengin-network");
  assert.equal(start.idempotencyKey, `sha256:${sha(fixture("zengin-incoming-sample.txt"))}`);
  assert.equal(fake.calls[2].body.items[0].referenceNo, "ZG-0005-1124-0000000001");
  assert.equal(fake.calls[3].body.status, "COMPLETED");

  fake.calls.length = 0;
  const again = await post("/api/v1/zengin/incoming", fixture("zengin-incoming-sample.txt"), "text/plain; charset=utf-8",
    { "X-Consumer-Username": "zengin-network" });
  assert.equal(again.status, 200);
  assert.equal(again.headers.get("idempotent-replayed"), "true");
  assert.deepEqual(await again.json(), body);
  assert.deepEqual(businessCalls(), []);
});

test("同じ Idempotency-Key で内容が違うと 422、処理中なら 409", async () => {
  const h = { "X-Consumer-Username": "fx-provider", "Idempotency-Key": "fx-batch-20261124-01" };
  assert.equal((await post("/api/v1/fx/rates", fixture("fx-rates-sample.json"), "application/json", h)).status, 200);
  const other = JSON.stringify({ provider: "other", rates: [] });
  const reused = await post("/api/v1/fx/rates", other, "application/json", h);
  assert.equal(reused.status, 422);
  assert.match((await reused.json()).title, /冪等キー/);
  fake.forceInProgress = true;
  const busy = await post("/api/v1/fx/rates", fixture("fx-rates-sample.json"), "application/json", { "X-Consumer-Username": "fx-provider", "Idempotency-Key": "fx-batch-20261124-02" });
  fake.forceInProgress = false;
  assert.equal(busy.status, 409);
  assert.equal(busy.headers.get("retry-after"), "30");
});

test("Shift_JIS のファイルも取り込める", async () => {
  const res = await post("/api/v1/zengin/incoming", fixture("zengin-incoming-sample.sjis.txt"), "text/plain; charset=Shift_JIS");
  assert.equal(res.status, 200);
  assert.equal((await res.json()).remitterNameKana, "ｶ)ﾃｽﾄｼﾖｳｼﾞ");
});

test("セッション切れ（401）なら 1 回だけ再ログインして再送する", async () => {
  fake.calls.length = 0;
  fake.forceExpire = true;
  const res = await post("/api/v1/fx/rates", JSON.stringify({ provider: "p", rates: [{ currency: "USD", mid: 150.1, spread: 1 }] }), "application/json");
  assert.equal(res.status, 200);
  assert.deepEqual(fake.calls.map((c) => c.path).slice(0, 3), ["/integration-run-start", "/login", "/integration-run-start"]);
});

test("検証 NG は勘定系の業務 API を呼ばずに 422（problem+json・行番号付き）。結果は完了として保存する", async () => {
  fake.calls.length = 0;
  const broken = fixture("zengin-incoming-sample.txt").toString("utf8").replace("000000125000", "000000999999");
  const res = await post("/api/v1/zengin/incoming", broken, "text/plain");
  assert.equal(res.status, 422);
  assert.equal(res.headers.get("content-type"), "application/problem+json");
  const p = await res.json();
  assert.equal(p.type, "https://bank.example/problems/invalid-input");
  assert.match(p.errors[0].message, /合計金額/);
  assert.deepEqual(businessCalls(), []);
  assert.equal(fake.calls.at(-1).body.status, "COMPLETED");
});

test("勘定系の障害は再試行し、回復すれば成功する。回復しなければ 502 で、実行は再処理できる（FAILED）", async () => {
  fake.calls.length = 0;
  fake.failWith = 503;
  fake.failTimes = 2;
  const ok = await post("/api/v1/billing/import", fixture("billing-sample.csv"), "text/csv", { "Idempotency-Key": "billing-retry-0001" });
  assert.equal(ok.status, 200);
  assert.equal(businessCalls().filter((p) => p === "/billing-import").length, 3);

  fake.failTimes = 10;
  const down = await post("/api/v1/billing/import", fixture("billing-sample.csv"), "text/csv", { "Idempotency-Key": "billing-retry-0002" });
  assert.equal(down.status, 502);
  assert.equal((await down.json()).type, "https://bank.example/problems/core-unavailable");
  assert.equal(fake.calls.at(-1).body.status, "FAILED");
  fake.failTimes = 0;
  // 同じキーで再送すると、もう一度処理される（RETRY）
  const retried = await post("/api/v1/billing/import", fixture("billing-sample.csv"), "text/csv", { "Idempotency-Key": "billing-retry-0002" });
  assert.equal(retried.status, 200);
  fake.failWith = null;
});

test("勘定系の業務エラー（4xx）は再試行せずに 422", async () => {
  fake.calls.length = 0;
  fake.failWith = 400;
  fake.failTimes = 1;
  const res = await post("/api/v1/billing/import", fixture("billing-sample.csv"), "text/csv", { "Idempotency-Key": "billing-reject-001" });
  assert.equal(res.status, 422);
  assert.equal((await res.json()).errors[0].message, "勘定系のエラー");
  assert.equal(businessCalls().filter((p) => p === "/billing-import").length, 1);
  fake.failWith = null;
});

test("文字コードが不正・JSON でない・未定義のパスは 422 / 404", async () => {
  let res = await post("/api/v1/zengin/incoming", Buffer.from([0xff, 0xfe, 0xfd]), "text/plain; charset=utf-8");
  assert.equal(res.status, 422);
  res = await post("/api/v1/fx/rates", "{not json", "application/json");
  assert.equal(res.status, 422);
  res = await fetch(`${appUrl}/api/v1/nothing`);
  assert.equal(res.status, 404);
});

test("連携コンソール（bank-ops）は署名付きの行員トークンが必須。作成者・承認者を記録し、本文の差し替えと平文ヘッダーは拒否・無視する", async () => {
  const body = fixture("fx-rates-sample.json");
  const ops = { "X-Consumer-Username": "bank-ops" };
  assert.equal((await post("/api/v1/fx/rates", body, "application/json", { ...ops, "X-Bank-Operator": "supervisor1" })).status, 403);

  const token = signOperatorToken({ maker: "supervisor1", approver: "supervisor2", approvalId: "6b1f0e9e-1111-4c1d-9c55-000000000001", bodySha256: sha(body) }, OPERATOR_SECRET);
  const ok = await post("/api/v1/fx/rates", body, "application/json", { ...ops, "X-Bank-Operator-Token": token });
  assert.equal(ok.status, 200);
  const started = fake.calls.filter((c) => c.path === "/integration-run-start").at(-1).body;
  assert.equal(started.operator, "supervisor1");
  assert.equal(started.approver, "supervisor2");
  assert.equal(started.idempotencyKey, "approval:6b1f0e9e-1111-4c1d-9c55-000000000001");

  const tampered = await post("/api/v1/fx/rates", JSON.stringify({ provider: "x", rates: [] }), "application/json", { ...ops, "X-Bank-Operator-Token": token });
  assert.equal(tampered.status, 403);
  const selfApproved = signOperatorToken({ maker: "supervisor1", approver: "supervisor1", approvalId: "a-2", bodySha256: sha(body) }, OPERATOR_SECRET);
  assert.equal((await post("/api/v1/fx/rates", body, "application/json", { ...ops, "X-Bank-Operator-Token": selfApproved })).status, 403);

  await post("/api/v1/fx/rates", JSON.stringify({ provider: "spoof", rates: [] }), "application/json",
    { "X-Consumer-Username": "fx-provider", "X-Bank-Operator": "spoofed" });
  const runs = await (await fetch(`${appUrl}/api/v1/runs`)).json();
  const spoof = runs.find((r) => r.consumer === "fx-provider" && r.operator === "spoofed");
  assert.equal(spoof, undefined);
  assert.ok(runs.some((r) => r.operator === "supervisor1" && r.approver === "supervisor2"));
  assert.equal(JSON.stringify(runs).includes("1000003"), false);
});

test("ログインできなければ 502", async () => {
  fake.loginOk = false;
  fake.session = "expired";
  const res = await post("/api/v1/fx/rates", JSON.stringify({ provider: "q", rates: [] }), "application/json");
  assert.equal(res.status, 502);
  fake.loginOk = true;
});

test("平文のポートは /live と /metrics だけ（業務 API は置かない）", async () => {
  const admin = `http://127.0.0.1:${appServer.admin.address().port}`;
  assert.equal((await fetch(`${admin}/live`)).status, 200);
  const m = await (await fetch(`${admin}/metrics`)).text();
  assert.match(m, /mule_integration_runs_total\{kind="ZENGIN_INCOMING",outcome="PARTIAL"\} \d+/);
  assert.match(m, /mule_idempotent_replays_total/);
  assert.match(m, /mule_core_calls_total\{path="\/billing-import",result="retry"\}/);
  assert.equal((await fetch(`${admin}/api/v1/health`)).status, 404);
});

// ---- mTLS（openssl がある環境だけ。CI の Ubuntu・Git Bash には入っている）----
function opensslAvailable() {
  try { execFileSync("openssl", ["version"], { stdio: "ignore" }); return true; } catch { return false; }
}

test("mTLS：社内 CA のクライアント証明書で CN が許可されたものだけを受け付ける", { skip: !opensslAvailable() && "openssl がありません" }, async () => {
  const dir = mkdtempSync(join(tmpdir(), "mtls-"));
  const o = (...args) => execFileSync("openssl", args, { cwd: dir, stdio: "ignore", env: { ...process.env, MSYS_NO_PATHCONV: "1" } });
  o("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=test-ca", "-keyout", "ca.key", "-out", "ca.crt");
  writeFileSync(join(dir, "srv.ext"), "subjectAltName=IP:127.0.0.1\n");
  for (const [name, cn, ext] of [["srv", "mule", "srv.ext"], ["kong", "kong-gateway", null], ["other", "someone-else", null]]) {
    o("req", "-newkey", "rsa:2048", "-nodes", "-subj", `/CN=${cn}`, "-keyout", `${name}.key`, "-out", `${name}.csr`);
    o("x509", "-req", "-in", `${name}.csr`, "-CA", "ca.crt", "-CAkey", "ca.key", "-CAcreateserial", "-days", "1", "-out", `${name}.crt`, ...(ext ? ["-extfile", ext] : []));
  }
  const f = (n) => readFileSync(join(dir, n));
  const core = createCoreClient({ baseUrl: coreUrl, user: "svc-mule", password: "Test-Pass-2026", backoffMs: 5 });
  const app = createApp({ core, log: () => undefined, requireClientCert: true, allowedClients: ["kong-gateway"] });
  const server = createTlsServer({ cert: f("srv.crt"), key: f("srv.key"), ca: f("ca.crt"), requestCert: true, rejectUnauthorized: true }, app);
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const call = (cert, key) => new Promise((resolve) => {
    const req = httpsRequest({ host: "127.0.0.1", port: server.address().port, path: "/api/v1/health", ca: f("ca.crt"), cert, key }, (res) => {
      res.resume();
      resolve(res.statusCode);
    });
    req.on("error", () => resolve("tls-error"));
    req.end();
  });
  try {
    assert.equal(await call(f("kong.crt"), f("kong.key")), 200);
    assert.equal(await call(f("other.crt"), f("other.key")), 403);
    assert.equal(await call(undefined, undefined), "tls-error");
  } finally {
    server.close();
  }
});


// HTTP の振る舞いのテスト：偽の勘定系 API を立て、取込・エラー応答（RFC 9457）・セッション切れの再ログイン・実行履歴を確かめる。
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { readFileSync } from "node:fs";
import { createApp } from "../src/server.mjs";
import { createCoreClient } from "../src/core.mjs";

const fixture = (name) => readFileSync(new URL(`../../mulesoft/bank-integration/src/test/resources/fixtures/${name}`, import.meta.url));

// 偽の勘定系：ログインでセッションを発行し、セッションが無ければ 401。forceExpire で 1 回だけ 401 を返す
const fake = { calls: [], session: "s1", forceExpire: false, failWith: null, loginOk: true };
let coreServer, appServer, coreUrl, appUrl;

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
    if (fake.failWith) return json(res, fake.failWith, { error: { code: "X", message: "勘定系のエラー" } });
    const b = JSON.parse(body);
    if (path === "/remit-incoming") {
      return json(res, 200, { data: b.items.map((it) => ({ status: it.nameKana === "ｻﾄｳ ﾊﾅｺ" ? "CREDITED" : "HELD", note: it.nameKana === "ｻﾄｳ ﾊﾅｺ" ? null : "受取人名不一致" })) });
    }
    if (path === "/billing-import") return json(res, 200, { data: b.items.map((it) => ({ ...it, result: it.billerCode === "ELEC01" ? "取込" : "契約なし" })) });
    if (path === "/fx-rate-update") return json(res, 200, { data: [] });
    return json(res, 404, { error: { code: "NOT_FOUND", message: "なし" } });
  });
  coreUrl = `http://127.0.0.1:${coreServer.address().port}/bank/api`;
  const core = createCoreClient({ baseUrl: coreUrl, user: "svc-mule", password: "Test-Pass-2026" });
  appServer = await startServer(createApp({ core, log: () => undefined }));
  appUrl = `http://127.0.0.1:${appServer.address().port}`;
});

after(() => {
  coreServer.close();
  appServer.close();
});

const post = (path, body, contentType, headers = {}) =>
  fetch(`${appUrl}${path}`, { method: "POST", body, headers: { "Content-Type": contentType, ...headers } });

test("全銀データを取り込み、入金・保留を集計して返す（サービスアカウントでログインする）", async () => {
  fake.calls.length = 0;
  const res = await post("/api/v1/zengin/incoming", fixture("zengin-incoming-sample.txt"), "text/plain; charset=utf-8",
    { "X-Request-ID": "req-1", "X-Consumer-Username": "zengin-network" });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("x-request-id"), "req-1");
  const body = await res.json();
  assert.deepEqual(body.summary, { credited: 1, held: 1, duplicate: 0, rejected: 0 });
  assert.equal(body.results[1].note, "受取人名不一致");
  assert.deepEqual(fake.calls.map((c) => c.path), ["/login", "/remit-incoming"]);
  assert.deepEqual(fake.calls[0].body, { userId: "svc-mule", password: "Test-Pass-2026" });
  const sent = fake.calls[1];
  assert.equal(sent.headers["x-requested-with"], "XMLHttpRequest");
  assert.equal(sent.headers["x-request-id"], "req-1");
  assert.equal(sent.body.items[0].referenceNo, "ZG-0005-1124-0000000001");
});

test("Shift_JIS のファイルも取り込める", async () => {
  const res = await post("/api/v1/zengin/incoming", fixture("zengin-incoming-sample.sjis.txt"), "text/plain; charset=Shift_JIS");
  assert.equal(res.status, 200);
  assert.equal((await res.json()).remitterNameKana, "ｶ)ﾃｽﾄｼﾖｳｼﾞ");
});

test("セッション切れ（401）なら 1 回だけ再ログインして再送する", async () => {
  fake.calls.length = 0;
  fake.forceExpire = true;
  const res = await post("/api/v1/fx/rates", fixture("fx-rates-sample.json"), "application/json");
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json().then((b) => [b.updated, b.skipped]), [["USD", "EUR"], ["GBP"]]);
  assert.deepEqual(fake.calls.map((c) => c.path), ["/fx-rate-update", "/login", "/fx-rate-update", "/fx-rate-update"]);
  assert.deepEqual(fake.calls[2].body, { currency: "USD", ttm: "152.30", spread: "1.00" });
});

test("検証 NG は勘定系を呼ばずに 422（problem+json・行番号付き）", async () => {
  fake.calls.length = 0;
  const broken = fixture("zengin-incoming-sample.txt").toString("utf8").replace("000000125000", "000000999999");
  const res = await post("/api/v1/zengin/incoming", broken, "text/plain");
  assert.equal(res.status, 422);
  assert.equal(res.headers.get("content-type"), "application/problem+json");
  const p = await res.json();
  assert.equal(p.type, "https://bank.example/problems/invalid-input");
  assert.match(p.errors[0].message, /合計金額/);
  assert.equal(fake.calls.length, 0);
});

test("勘定系の業務エラーは 422、障害は 502", async () => {
  fake.failWith = 400;
  let res = await post("/api/v1/billing/import", fixture("billing-sample.csv"), "text/csv");
  assert.equal(res.status, 422);
  assert.equal((await res.json()).errors[0].message, "勘定系のエラー");
  fake.failWith = 503;
  res = await post("/api/v1/billing/import", fixture("billing-sample.csv"), "text/csv");
  assert.equal(res.status, 502);
  assert.equal((await res.json()).type, "https://bank.example/problems/core-unavailable");
  fake.failWith = null;
});

test("請求データを取り込み、結果を返す", async () => {
  const res = await post("/api/v1/billing/import", fixture("billing-sample.csv"), "text/csv; charset=utf-8");
  assert.deepEqual(await res.json().then((b) => b.results.map((r) => r.result)), ["取込", "契約なし"]);
});

test("文字コードが不正・JSON でない・未定義のパスは 422 / 404", async () => {
  let res = await post("/api/v1/zengin/incoming", Buffer.from([0xff, 0xfe, 0xfd]), "text/plain; charset=utf-8");
  assert.equal(res.status, 422);
  res = await post("/api/v1/fx/rates", "{not json", "application/json");
  assert.equal(res.status, 422);
  res = await fetch(`${appUrl}/api/v1/nothing`);
  assert.equal(res.status, 404);
});

test("ログインできなければ 502", async () => {
  fake.loginOk = false;
  fake.session = "expired";
  const res = await post("/api/v1/fx/rates", fixture("fx-rates-sample.json"), "application/json");
  assert.equal(res.status, 502);
  fake.loginOk = true;
});

test("稼働状況と実行履歴（行員名は bank-ops 経由のときだけ記録）", async () => {
  await post("/api/v1/fx/rates", fixture("fx-rates-sample.json"), "application/json",
    { "X-Consumer-Username": "bank-ops", "X-Bank-Operator": "supervisor1" });
  await post("/api/v1/fx/rates", fixture("fx-rates-sample.json"), "application/json",
    { "X-Consumer-Username": "fx-provider", "X-Bank-Operator": "spoofed" });
  const health = await (await fetch(`${appUrl}/api/v1/health`)).json();
  assert.deepEqual(health, { status: "UP", runtime: "mule-compat", core: "UP" });
  const runs = await (await fetch(`${appUrl}/api/v1/runs`)).json();
  assert.equal(runs[0].consumer, "fx-provider");
  assert.equal(runs[0].operator, null);
  assert.equal(runs[1].operator, "supervisor1");
  assert.ok(runs.some((r) => r.kind === "ZENGIN_INCOMING" && r.outcome === "PARTIAL"));
  assert.ok(runs.some((r) => r.outcome === "FAILURE"));
  assert.ok(runs.length <= 50);
  assert.equal(JSON.stringify(runs).includes("1000003"), false);
});

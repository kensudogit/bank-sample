// Agent のテスト：偽の Messages API（HTTP）を立て、実際の SDK（tool runner）を通して、
// トークンの検証・予算・On-Behalf-Of・スコープによるツールの絞り込み・勘定系の拒否・費用の上限・提案・記録を確かめる。
// 本物の Claude API は呼ばない（費用がかからない）。
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { generateKeyPairSync, sign } from "node:crypto";
import Anthropic from "@anthropic-ai/sdk";
import { createAgent, estimateJpy } from "../src/server.mjs";
import { createJwks } from "../src/identity.mjs";

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: "jwk" }), kid: "k1", alg: "RS256", use: "sig" };
const TENANT = "11111111-2222-4333-8444-555555555555";
const ISS = "https://auth.test";
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
function jwt(claims, key = privateKey, kid = "k1") {
  const now = Math.floor(Date.now() / 1000);
  const input = `${b64({ alg: "RS256", typ: "JWT", kid })}.${b64({ iss: ISS, aud: "api://bank-agent", tid: TENANT, iat: now, nbf: now, exp: now + 600,
    oid: "oid-teller1", preferred_username: "teller1", scp: "Agent.Invoke", ...claims })}`;
  return `${input}.${sign("sha256", Buffer.from(input), key).toString("base64url")}`;
}

// 偽の Messages API：scripted に積んだ応答を順に返し、要求を記録する
const llm = { requests: [], scripted: [] };
let llmServer;
let baseURL;
before(async () => {
  llmServer = createServer(async (req, res) => {
    let body = "";
    for await (const c of req) body += c;
    llm.requests.push(JSON.parse(body));
    const next = llm.scripted.shift() ?? { content: [{ type: "text", text: "おわり" }], stop_reason: "end_turn" };
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      id: `msg_${llm.requests.length}`, type: "message", role: "assistant", model: "claude-opus-5-5", stop_sequence: null,
      usage: { input_tokens: 1000, output_tokens: 200, cache_read_input_tokens: 500, cache_creation_input_tokens: 0 }, ...next,
    }));
  });
  await new Promise((r) => llmServer.listen(0, "127.0.0.1", r));
  baseURL = `http://127.0.0.1:${llmServer.address().port}`;
});
after(() => llmServer.close());

function setup({ scopes = ["Accounts.Read", "Transactions.Read", "Fx.Read"], budgetAllowed = true, coreStatus = 200, maxRunJpy = 50 } = {}) {
  llm.requests.length = 0;
  const calls = [];
  const fetchImpl = async (url, init = {}) => {
    calls.push({ url, init });
    const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
    if (url.endsWith("/jwks")) return json(200, { keys: [jwk] });
    if (url.includes("/agent/v1/meter/budget")) return json(200, { data: { allowed: budgetAllowed, reason: budgetAllowed ? null : "予算超過" } });
    if (url.includes("/agent/v1/meter/usage")) return json(200, { data: { costJpy: 1.23 } });
    if (url.includes("/agent/v1/bank/")) return json(coreStatus, { data: { accountNo: "1000001", balance: 500000, holder: "ヤマダ タロウ" } });
    throw new Error(`unexpected ${url}`);
  };
  const tokens = {
    obo: [],
    async onBehalfOf(userToken, scope) { this.obo.push({ userToken, scope }); return { token: "core-token", scopes }; },
    async appToken() { return "meter-token"; },
  };
  const handler = createAgent({
    agentId: "bank-agent", issuer: ISS, tenants: [TENANT], agentAudience: "api://bank-agent", coreAudience: "api://bank-core",
    gatewayUrl: "http://kong", model: "claude-opus-5-5", effort: "medium", maxTokens: 1024, maxIterations: 4, maxRunJpy,
    price: { input: 4, output: 20, cacheRead: 0.2, cacheWrite: 5, fx: 150 },
    keyFor: createJwks("http://auth/jwks", fetchImpl), tokens, fetchImpl,
    anthropic: new Anthropic({ apiKey: "test-key", baseURL, maxRetries: 0 }), log: () => {},
  });
  return { handler, calls, tokens };
}

async function call(handler, token, prompt = "1000001 の残高は？") {
  const server = createServer(handler);
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  try {
    const res = await fetch(`http://127.0.0.1:${server.address().port}/v1/runs`, {
      method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" }, body: JSON.stringify({ prompt }),
    });
    return { status: res.status, body: await res.json() };
  } finally {
    server.close();
  }
}

test("行員の委任トークンで実行し、On-Behalf-Of のトークンで勘定系を参照して答える。実行とトークン数を記録する", async () => {
  const { handler, calls, tokens } = setup();
  llm.scripted.push({ content: [{ type: "tool_use", id: "tu_1", name: "get_account", input: { accountNo: "1000001" } }], stop_reason: "tool_use" });
  llm.scripted.push({ content: [{ type: "text", text: "残高は 500,000 円です。\n以上です。" }], stop_reason: "end_turn" });
  const user = jwt({});
  const { status, body } = await call(handler, user);
  assert.equal(status, 200);
  assert.equal(body.status, "COMPLETED");
  assert.equal(body.answer, "残高は 500,000 円です。\n以上です。");
  assert.deepEqual(body.toolCalls.map((c) => [c.name, c.status]), [["get_account", "OK"]]);
  assert.equal(body.costJpy, 1.23);
  assert.deepEqual(body.usage, { inputTokens: 2000, outputTokens: 400, cacheReadTokens: 1000, cacheWriteTokens: 0 });
  // OBO は受け取った行員のトークンを勘定系の API 宛てに交換する
  assert.deepEqual(tokens.obo, [{ userToken: user, scope: "api://bank-core/.default" }]);
  const core = calls.find((c) => c.url.includes("/agent/v1/bank/"));
  assert.equal(core.url, "http://kong/agent/v1/bank/account?accountNo=1000001");
  assert.equal(core.init.headers.Authorization, "Bearer core-token");
  assert.match(core.init.headers.traceparent, /^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
  // 記録：Agent の ID は送らない（勘定系がトークンから決める）。行員・状態・ツール・トークン数
  const usage = JSON.parse(calls.find((c) => c.url.includes("/meter/usage")).init.body);
  assert.equal(usage.principal, "teller1");
  assert.equal(usage.status, "COMPLETED");
  assert.equal(usage.toolCalls, "get_account:OK");
  assert.equal(usage.inputTokens, "2000");
  assert.equal(usage.agentId, undefined);
  assert.match(usage.promptSha256, /^[0-9a-f]{64}$/);
  // 勘定系の入力検証（制御文字の禁止）に合わせ、要約の改行は空白にする
  assert.equal(usage.summary, "残高は 500,000 円です。 以上です。");
  // Claude への要求：既定のモデル・effort・キャッシュ・サーバー側のフォールバック・strict のツール
  const req = llm.requests[0];
  assert.equal(req.model, "claude-opus-5-5");
  assert.deepEqual(req.output_config, { effort: "medium" });
  assert.deepEqual(req.cache_control, { type: "ephemeral" });
  assert.equal(req.fallbacks, "default");
  assert.ok(req.tools.every((t) => t.strict === true));
});

test("トークンのスコープにないツールはモデルに見せない（Customers.Read なし → 顧客のツールなし）", async () => {
  const { handler } = setup({ scopes: ["Fx.Read"] });
  await call(handler, jwt({}));
  assert.deepEqual(llm.requests[0].tools.map((t) => t.name), ["get_fx_rates"]);
});

test("勘定系が拒否（403）したら、許可されていないと伝えて DENIED を記録する", async () => {
  const { handler, calls } = setup({ coreStatus: 403 });
  llm.scripted.push({ content: [{ type: "tool_use", id: "tu_1", name: "get_account", input: { accountNo: "1000001" } }], stop_reason: "tool_use" });
  llm.scripted.push({ content: [{ type: "text", text: "権限がありません。" }], stop_reason: "end_turn" });
  const { body } = await call(handler, jwt({}));
  assert.equal(body.toolCalls[0].status, "DENIED");
  const toolResult = llm.requests[1].messages.at(-1).content[0];
  assert.match(JSON.stringify(toolResult.content), /許可されていません/);
  assert.equal(JSON.parse(calls.find((c) => c.url.includes("/meter/usage")).init.body).deniedCalls, "1");
});

test("お金が動く操作は実行せず提案として返す（勘定系を呼ばない）", async () => {
  const { handler, calls } = setup();
  llm.scripted.push({ content: [{ type: "tool_use", id: "tu_1", name: "propose_transfer", input: { fromAccountNo: "1000001", toAccountNo: "1000002", amount: 5000, memo: "立替" } }], stop_reason: "tool_use" });
  llm.scripted.push({ content: [{ type: "text", text: "振込画面で確認してください。" }], stop_reason: "end_turn" });
  const { body } = await call(handler, jwt({}));
  assert.deepEqual(body.proposals, [{ type: "transfer", fromAccountNo: "1000001", toAccountNo: "1000002", amount: 5000, memo: "立替", requiresHumanApproval: true }]);
  assert.equal(calls.filter((c) => c.url.includes("/agent/v1/bank/")).length, 0);
});

test("予算の上限に達した Agent は実行しない（Claude も OBO も呼ばない）", async () => {
  const { handler, tokens, calls } = setup({ budgetAllowed: false });
  const { body } = await call(handler, jwt({}));
  assert.equal(body.status, "BLOCKED");
  assert.equal(llm.requests.length, 0);
  assert.equal(tokens.obo.length, 0);
  assert.equal(JSON.parse(calls.find((c) => c.url.includes("/meter/usage")).init.body).status, "BLOCKED");
});

test("1 回の費用の上限を超えたら打ち切る（LIMITED）", async () => {
  const { handler } = setup({ maxRunJpy: 0.1 });
  llm.scripted.push({ content: [{ type: "tool_use", id: "tu_1", name: "get_fx_rates", input: {} }], stop_reason: "tool_use" });
  const { body } = await call(handler, jwt({}));
  assert.equal(body.status, "LIMITED");
  assert.equal(llm.requests.length, 1);
  assert.ok(estimateJpy({ input: 1000, output: 200, cacheRead: 500, cacheWrite: 0 }, { input: 4, output: 20, cacheRead: 0.2, cacheWrite: 5, fx: 150 }) > 0.1);
});

test("トークンの拒否：署名の改ざん・宛先違い・アプリのみ・他テナント・スコープなし・期限切れ（Claude は呼ばない）", async () => {
  const { handler } = setup();
  const other = generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey;
  const now = Math.floor(Date.now() / 1000);
  const cases = [
    [jwt({}, other), 401],
    [jwt({ aud: "api://bank-core" }), 401],
    [jwt({ idtyp: "app" }), 403],
    [jwt({ tid: "99999999-2222-4333-8444-555555555555" }), 403],
    [jwt({ scp: "Accounts.Read" }), 403],
    [jwt({ exp: now - 10 }), 401],
    [jwt({}, privateKey, "unknown-kid"), 401],
  ];
  for (const [token, expected] of cases) {
    const { status } = await call(handler, token);
    assert.equal(status, expected);
  }
  assert.equal(llm.requests.length, 0);
});

test("質問の検証：空・2000 文字超は 422", async () => {
  const { handler } = setup();
  assert.equal((await call(handler, jwt({}), " ")).status, 422);
  assert.equal((await call(handler, jwt({}), "あ".repeat(2001))).status, 422);
});

test("strict のツールの引数に、API が受け付けないキーワード（integer の minimum・maximum）を使わない", async () => {
  const { TOOL_SPECS } = await import("../src/tools.mjs");
  for (const s of TOOL_SPECS) {
    for (const [name, p] of Object.entries(s.schema.properties ?? {})) {
      if (p.type === "integer" || p.type === "number") {
        assert.equal(p.minimum ?? p.maximum ?? p.exclusiveMinimum ?? p.exclusiveMaximum, undefined, `${s.name}.${name}`);
      }
    }
    assert.equal(s.schema.additionalProperties, false, s.name);
    assert.deepEqual([...(s.schema.required ?? [])].sort(), Object.keys(s.schema.properties ?? {}).sort(), `${s.name}: strict では全項目を required に`);
  }
});

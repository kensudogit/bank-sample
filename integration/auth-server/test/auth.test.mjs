import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, verify } from "node:crypto";
import { createServer } from "node:http";
import { GRANT_JWT_BEARER, GRANT_SESSION_BRIDGE, createAuthServer, parseClients, parseObo, readPem, stableUuid } from "../src/server.mjs";

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const pem = privateKey.export({ type: "pkcs8", format: "pem" });
const sha = (s) => createHash("sha256").update(s).digest("hex");
const SECRET = "zengin-secret-0123456789abcdefghijklmnop";
const CONSOLE = "console-secret-0123456789abcdefghijklmn";
const AGENT = "agent-secret-0123456789abcdefghijklmnopq";
const TENANT = "11111111-2222-4333-8444-555555555555";
const clients = parseClients(`zengin-network:${sha(SECRET)}:Zengin.Submit+Integration.Health,bank-ops:${sha("ops-secret-0123456789abcdefghijklmnopq")}:Ops.Read,bank-console:${sha(CONSOLE)}:,bank-agent:${sha(AGENT)}:Agent.Meter`);
const obo = parseObo("bank-agent>api://bank-agent>api://bank-core:Accounts.Read+Transactions.Read+Fx.Read");

async function withServer(fn, opts = {}) {
  let t = Date.UTC(2026, 10, 24, 0, 0, 0);
  const handler = createAuthServer({
    issuer: "https://auth.test", audience: "api://bank-integration", tenantId: TENANT, signingKeyPem: pem, keyId: "k1", clients,
    bridgeClients: ["bank-console"], obo,
    now: () => t, log: () => {}, ...opts,
  });
  const server = createServer(handler);
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await fn(base, (ms) => { t += ms; });
  } finally {
    server.close();
  }
}

const form = (o) => new URLSearchParams(o).toString();
const post = (base, body, headers = {}) => fetch(`${base}/oauth2/token`, {
  method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded", ...headers }, body,
});
const decode = (part) => JSON.parse(Buffer.from(part, "base64url").toString("utf8"));

test("client_secret_basic で RS256 のトークンを発行し、署名・iss・aud・azp・roles・exp が正しい", async () => {
  await withServer(async (base) => {
    const res = await post(base, form({ grant_type: "client_credentials" }),
      { Authorization: `Basic ${Buffer.from(`zengin-network:${SECRET}`).toString("base64")}` });
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("cache-control"), "no-store");
    const body = await res.json();
    assert.equal(body.token_type, "Bearer");
    assert.equal(body.expires_in, 300);
    const [h, p, s] = body.access_token.split(".");
    assert.deepEqual(decode(h), { alg: "RS256", typ: "JWT", kid: "k1" });
    const claims = decode(p);
    assert.equal(claims.iss, "https://auth.test");
    assert.equal(claims.aud, "api://bank-integration");
    assert.equal(claims.azp, "zengin-network");
    assert.deepEqual(claims.roles, ["Zengin.Submit", "Integration.Health"]);
    assert.equal(claims.exp - claims.iat, 300);
    assert.ok(verify("sha256", Buffer.from(`${h}.${p}`), publicKey, Buffer.from(s, "base64url")));
  });
});

test("client_secret_post と、ロールを絞った scope も使える。付与されていないロールは invalid_scope", async () => {
  await withServer(async (base) => {
    const ok = await post(base, form({ grant_type: "client_credentials", client_id: "zengin-network", client_secret: SECRET, scope: "Integration.Health" }));
    assert.equal(ok.status, 200);
    assert.deepEqual(decode((await ok.json()).access_token.split(".")[1]).roles, ["Integration.Health"]);
    const bad = await post(base, form({ grant_type: "client_credentials", client_id: "zengin-network", client_secret: SECRET, scope: "Ops.Read" }));
    assert.equal(bad.status, 400);
    assert.equal((await bad.json()).error, "invalid_scope");
  });
});

test("シークレット違い・未登録は 401 invalid_client。5 回失敗すると 1 分間 429", async () => {
  await withServer(async (base, advance) => {
    for (let i = 0; i < 5; i++) {
      const r = await post(base, form({ grant_type: "client_credentials", client_id: "zengin-network", client_secret: "wrong" }));
      assert.equal(r.status, 401);
      assert.equal((await r.json()).error, "invalid_client");
    }
    const locked = await post(base, form({ grant_type: "client_credentials", client_id: "zengin-network", client_secret: SECRET }));
    assert.equal(locked.status, 429);
    advance(61_000);
    const ok = await post(base, form({ grant_type: "client_credentials", client_id: "zengin-network", client_secret: SECRET }));
    assert.equal(ok.status, 200);
    const unknown = await post(base, form({ grant_type: "client_credentials", client_id: "nobody", client_secret: SECRET }));
    assert.equal(unknown.status, 401);
  });
});

test("grant_type・Content-Type の誤りは 400。JWKS と OpenID の設定を公開する", async () => {
  await withServer(async (base) => {
    assert.equal((await post(base, form({ grant_type: "password" }))).status, 400);
    const json = await fetch(`${base}/oauth2/token`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
    assert.equal(json.status, 400);
    const jwks = await (await fetch(`${base}/.well-known/jwks.json`)).json();
    assert.equal(jwks.keys[0].kid, "k1");
    assert.equal(jwks.keys[0].kty, "RSA");
    assert.equal(jwks.keys[0].d, undefined);
    const conf = await (await fetch(`${base}/.well-known/openid-configuration`)).json();
    assert.equal(conf.token_endpoint, "https://auth.test/oauth2/token");
    const metrics = await (await fetch(`${base}/metrics`)).text();
    assert.match(metrics, /auth_token_failures_total \d+/);
  });
});

test("設定の読み込み：不正なクライアント定義は起動しない。PEM は Base64 1 行でもよい", () => {
  assert.throws(() => parseClients("x:notahash:Role"));
  assert.throws(() => parseClients(""));
  assert.equal(readPem(Buffer.from(pem).toString("base64")), pem);
});

const basicOf = (id, secret) => ({ Authorization: `Basic ${Buffer.from(`${id}:${secret}`).toString("base64")}` });
async function userToken(base, subject = "teller1", role = "TELLER") {
  const r = await post(base, form({ grant_type: GRANT_SESSION_BRIDGE, subject, subject_role: role }), basicOf("bank-console", CONSOLE));
  return { status: r.status, body: await r.json() };
}

test("アプリのみのトークンは Entra ID と同じ形（tid・oid・idtyp=app・ver）", async () => {
  await withServer(async (base) => {
    const r = await post(base, form({ grant_type: "client_credentials" }), basicOf("zengin-network", SECRET));
    const c = decode((await r.json()).access_token.split(".")[1]);
    assert.equal(c.tid, TENANT);
    assert.equal(c.idtyp, "app");
    assert.equal(c.ver, "2.0");
    assert.equal(c.oid, stableUuid("sp", TENANT, "zengin-network"));
    // ロールを割り当てていないクライアント（画面）は client_credentials で取れない
    const none = await post(base, form({ grant_type: "client_credentials" }), basicOf("bank-console", CONSOLE));
    assert.equal(none.status, 400);
  });
});

test("セッションブリッジ：画面だけが、行員の委任トークン（aud=api://bank-agent・scp=Agent.Invoke）を得られる", async () => {
  await withServer(async (base) => {
    const { status, body } = await userToken(base, "supervisor1", "SUPERVISOR");
    assert.equal(status, 200);
    const c = decode(body.access_token.split(".")[1]);
    assert.equal(c.aud, "api://bank-agent");
    assert.equal(c.scp, "Agent.Invoke");
    assert.equal(c.preferred_username, "supervisor1");
    assert.deepEqual(c.roles, ["Bank.Supervisor"]);
    assert.equal(c.oid, stableUuid("user", TENANT, "supervisor1"));
    assert.equal(c.idtyp, undefined);
    assert.equal(c.exp - c.iat, 600);
    // 画面以外のクライアント・顧客ロール・不正な利用者 ID は拒否
    const other = await post(base, form({ grant_type: GRANT_SESSION_BRIDGE, subject: "teller1", subject_role: "TELLER" }), basicOf("zengin-network", SECRET));
    assert.equal((await other.json()).error, "unauthorized_client");
    assert.equal((await userToken(base, "yamada", "CUSTOMER")).status, 400);
    assert.equal((await userToken(base, "a b", "TELLER")).status, 400);
  });
});

test("On-Behalf-Of：Agent が行員のトークンを勘定系向けに交換する（azp=Agent・行員の oid・同意済みスコープ）", async () => {
  await withServer(async (base) => {
    const user = (await userToken(base, "teller1", "TELLER")).body.access_token;
    const r = await post(base, form({ grant_type: GRANT_JWT_BEARER, requested_token_use: "on_behalf_of", assertion: user, scope: "api://bank-core/.default" }), basicOf("bank-agent", AGENT));
    assert.equal(r.status, 200);
    const c = decode((await r.json()).access_token.split(".")[1]);
    assert.equal(c.aud, "api://bank-core");
    assert.equal(c.azp, "bank-agent");
    assert.equal(c.preferred_username, "teller1");
    assert.equal(c.oid, stableUuid("user", TENANT, "teller1"));
    assert.equal(c.scp, "Accounts.Read Transactions.Read Fx.Read");
    // スコープを絞れる。同意していないスコープは invalid_scope
    const narrow = await post(base, form({ grant_type: GRANT_JWT_BEARER, requested_token_use: "on_behalf_of", assertion: user, scope: "api://bank-core/Fx.Read" }), basicOf("bank-agent", AGENT));
    assert.equal(decode((await narrow.json()).access_token.split(".")[1]).scp, "Fx.Read");
    const wide = await post(base, form({ grant_type: GRANT_JWT_BEARER, requested_token_use: "on_behalf_of", assertion: user, scope: "api://bank-core/Customers.Read" }), basicOf("bank-agent", AGENT));
    assert.equal((await wide.json()).error, "invalid_scope");
  });
});

test("On-Behalf-Of の拒否：OBO を許可していないクライアント・アプリのみのトークン・改ざん・期限切れ", async () => {
  await withServer(async (base, advance) => {
    const user = (await userToken(base)).body.access_token;
    const obo = (assertion, auth = basicOf("bank-agent", AGENT)) =>
      post(base, form({ grant_type: GRANT_JWT_BEARER, requested_token_use: "on_behalf_of", assertion }), auth);
    assert.equal((await (await obo(user, basicOf("zengin-network", SECRET))).json()).error, "unauthorized_client");
    const app = (await (await post(base, form({ grant_type: "client_credentials" }), basicOf("zengin-network", SECRET))).json()).access_token;
    assert.equal((await (await obo(app)).json()).error, "invalid_grant");
    const [h, p] = user.split(".");
    const forgedPayload = Buffer.from(JSON.stringify({ ...decode(p), preferred_username: "supervisor1" })).toString("base64url");
    assert.equal((await (await obo(`${h}.${forgedPayload}.${user.split(".")[2]}`)).json()).error, "invalid_grant");
    advance(601_000);
    assert.equal((await (await obo(user)).json()).error, "invalid_grant");
  });
});

test("OBO の設定：不正な書式は起動しない", () => {
  assert.throws(() => parseObo("bank-agent:api://x"));
  assert.equal(parseObo("").size, 0);
});
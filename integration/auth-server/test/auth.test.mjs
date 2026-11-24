import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, verify } from "node:crypto";
import { createServer } from "node:http";
import { createAuthServer, parseClients, readPem } from "../src/server.mjs";

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const pem = privateKey.export({ type: "pkcs8", format: "pem" });
const sha = (s) => createHash("sha256").update(s).digest("hex");
const SECRET = "zengin-secret-0123456789abcdefghijklmnop";
const clients = parseClients(`zengin-network:${sha(SECRET)}:Zengin.Submit+Integration.Health,bank-ops:${sha("ops-secret-0123456789abcdefghijklmnopq")}:Ops.Read`);

async function withServer(fn, opts = {}) {
  let t = Date.UTC(2026, 10, 24, 0, 0, 0);
  const handler = createAuthServer({
    issuer: "https://auth.test", audience: "api://bank-integration", signingKeyPem: pem, keyId: "k1", clients,
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

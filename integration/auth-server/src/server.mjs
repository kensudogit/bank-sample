// OAuth 2.0 トークン発行サーバー（クライアントクレデンシャル。RS256 の JWT アクセストークン）。
// 連携先（他行・収納企業・相場提供元）と連携コンソール（bank-ops）は、client_id / client_secret でトークンを取り、
// Kong に Authorization: Bearer で送る。Kong はトークンの署名・発行者（iss）・宛先（aud）・期限（exp）・ロール（roles）を検証する。
//
// 学習用の自前の発行サーバー。本番では Microsoft Entra ID などの IdP を使う（Kong の設定を AUTH_MODE=entra に切り替える。
// トークンの形式（iss・aud・azp・roles）は Entra ID のクライアントクレデンシャルのアクセストークンに合わせてある）。
// 依存パッケージなし（Node.js 標準機能だけ）。

import { createServer } from "node:http";
import { createHash, createPrivateKey, createPublicKey, randomUUID, sign, timingSafeEqual } from "node:crypto";
import { pathToFileURL } from "node:url";

const MAX_BODY = 8 * 1024;

/** base64url（パディングなし） */
export function b64url(buf) {
  return Buffer.from(buf).toString("base64").replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
}

/**
 * クライアント一覧を読む。形式：「client_id:sha256(secret) の 16 進:ロール+ロール」をカンマ区切り。
 * シークレットそのものはこのサービスに置かず、ハッシュだけを持つ（シークレットは 32 文字以上のランダム値）。
 */
export function parseClients(spec) {
  const clients = new Map();
  for (const entry of String(spec ?? "").split(",").map((s) => s.trim()).filter(Boolean)) {
    const [id, hash, roles] = entry.split(":");
    if (!/^[A-Za-z0-9_.-]{3,64}$/.test(id ?? "") || !/^[0-9a-f]{64}$/.test(hash ?? "") || !roles) {
      throw new Error(`invalid AUTH_CLIENTS entry for ${id ?? "?"}`);
    }
    clients.set(id, { id, hash: Buffer.from(hash, "hex"), roles: roles.split("+").filter((r) => /^[A-Za-z0-9_.]{1,64}$/.test(r)) });
  }
  if (clients.size === 0) throw new Error("AUTH_CLIENTS has no clients");
  return clients;
}

/** PEM（そのまま、または Base64 で 1 行にしたもの）を読む */
export function readPem(value) {
  const v = String(value ?? "").trim();
  if (!v) throw new Error("AUTH_SIGNING_KEY is not set");
  return v.startsWith("-----BEGIN") ? v : Buffer.from(v, "base64").toString("utf8");
}

export function createAuthServer({
  issuer, audience, signingKeyPem, keyId, clients, ttlSeconds = 300, now = () => Date.now(), log = console.log,
}) {
  const privateKey = createPrivateKey(signingKeyPem);
  const jwk = { ...createPublicKey(privateKey).export({ format: "jwk" }), kid: keyId, use: "sig", alg: "RS256" };
  const metrics = { issued: new Map(), failures: 0 };
  // client_id ごとの連続失敗（総当たり対策。Kong の IP ごとの制限に加える）
  const failures = new Map();

  function issue(client, roles) {
    const iat = Math.floor(now() / 1000);
    const header = { alg: "RS256", typ: "JWT", kid: keyId };
    const payload = {
      iss: issuer, aud: audience, sub: client.id, azp: client.id, roles,
      iat, nbf: iat - 5, exp: iat + ttlSeconds, jti: randomUUID(),
    };
    const input = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(payload))}`;
    const signature = sign("sha256", Buffer.from(input), privateKey);
    return `${input}.${b64url(signature)}`;
  }

  function oauthError(res, status, error, description, extra = {}) {
    metrics.failures++;
    res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store", Pragma: "no-cache", ...extra });
    res.end(JSON.stringify({ error, error_description: description }));
  }

  function verifySecret(client, secret) {
    const presented = createHash("sha256").update(String(secret ?? ""), "utf8").digest();
    return timingSafeEqual(presented, client.hash);
  }

  async function readForm(req) {
    let size = 0;
    const chunks = [];
    for await (const c of req) {
      size += c.length;
      if (size > MAX_BODY) throw new Error("too large");
      chunks.push(c);
    }
    return new URLSearchParams(Buffer.concat(chunks).toString("utf8"));
  }

  async function token(req, res) {
    if (!/^application\/x-www-form-urlencoded/i.test(String(req.headers["content-type"] ?? ""))) {
      return oauthError(res, 400, "invalid_request", "Content-Type must be application/x-www-form-urlencoded");
    }
    let form;
    try {
      form = await readForm(req);
    } catch {
      return oauthError(res, 400, "invalid_request", "request body too large");
    }
    if (form.get("grant_type") !== "client_credentials") {
      return oauthError(res, 400, "unsupported_grant_type", "only client_credentials is supported");
    }
    // クライアント認証：client_secret_basic（推奨）または client_secret_post
    let id = form.get("client_id");
    let secret = form.get("client_secret");
    const basic = /^Basic\s+(.+)$/i.exec(String(req.headers.authorization ?? ""));
    if (basic) {
      const decoded = Buffer.from(basic[1], "base64").toString("utf8");
      const i = decoded.indexOf(":");
      id = decodeURIComponent(decoded.slice(0, i));
      secret = decodeURIComponent(decoded.slice(i + 1));
    }
    const client = clients.get(String(id ?? ""));
    const fail = failures.get(String(id ?? "")) ?? { count: 0, until: 0 };
    if (fail.until > now()) {
      return oauthError(res, 429, "temporarily_unavailable", "too many failed attempts; retry later", { "Retry-After": "60" });
    }
    if (!client || !verifySecret(client, secret)) {
      if (client) {
        fail.count++;
        if (fail.count >= 5) { fail.until = now() + 60_000; fail.count = 0; }
        failures.set(client.id, fail);
      }
      log(JSON.stringify({ event: "token-denied", clientId: String(id ?? "").slice(0, 64) }));
      return oauthError(res, 401, "invalid_client", "client authentication failed", { "WWW-Authenticate": 'Basic realm="bank-integration"' });
    }
    failures.delete(client.id);
    // scope：「<aud>/.default」（Entra ID と同じ書き方。割り当てられたロールすべて）か、ロール名の空白区切り
    const scope = (form.get("scope") ?? `${audience}/.default`).trim();
    let roles = client.roles;
    if (scope !== `${audience}/.default`) {
      const asked = scope.split(/\s+/).filter(Boolean);
      if (asked.length === 0 || asked.some((r) => !client.roles.includes(r))) {
        return oauthError(res, 400, "invalid_scope", "requested scope is not granted to this client");
      }
      roles = asked;
    }
    const accessToken = issue(client, roles);
    metrics.issued.set(client.id, (metrics.issued.get(client.id) ?? 0) + 1);
    log(JSON.stringify({ event: "token-issued", clientId: client.id, roles }));
    res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store", Pragma: "no-cache" });
    res.end(JSON.stringify({ access_token: accessToken, token_type: "Bearer", expires_in: ttlSeconds, scope: roles.join(" ") }));
  }

  function json(res, status, body, cache = "no-store") {
    res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": cache });
    res.end(JSON.stringify(body));
  }

  function prometheus(res) {
    const lines = [
      "# HELP auth_tokens_issued_total Access tokens issued per client.",
      "# TYPE auth_tokens_issued_total counter",
      ...[...metrics.issued].map(([c, n]) => `auth_tokens_issued_total{client_id="${c}"} ${n}`),
      "# HELP auth_token_failures_total Rejected token requests.",
      "# TYPE auth_token_failures_total counter",
      `auth_token_failures_total ${metrics.failures}`,
    ];
    res.writeHead(200, { "Content-Type": "text/plain; version=0.0.4" });
    res.end(lines.join("\n") + "\n");
  }

  return async function handle(req, res) {
    const path = new URL(req.url ?? "/", "http://localhost").pathname;
    try {
      if (req.method === "POST" && path === "/oauth2/token") return await token(req, res);
      if (req.method === "GET" && path === "/.well-known/jwks.json") return json(res, 200, { keys: [jwk] }, "public, max-age=300");
      if (req.method === "GET" && path === "/.well-known/openid-configuration") {
        return json(res, 200, {
          issuer, token_endpoint: `${issuer}/oauth2/token`, jwks_uri: `${issuer}/.well-known/jwks.json`,
          grant_types_supported: ["client_credentials"], token_endpoint_auth_methods_supported: ["client_secret_basic", "client_secret_post"],
          id_token_signing_alg_values_supported: ["RS256"],
        }, "public, max-age=300");
      }
      if (req.method === "GET" && path === "/live") return json(res, 200, { status: "UP" });
      if (req.method === "GET" && path === "/metrics") return prometheus(res);
      return json(res, 404, { error: "not_found" });
    } catch (e) {
      log(JSON.stringify({ event: "error", error: String(e?.stack ?? e) }));
      return json(res, 500, { error: "server_error" });
    }
  };
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const handler = createAuthServer({
    issuer: process.env.AUTH_ISSUER ?? "https://auth.bank-sample.local",
    audience: process.env.AUTH_AUDIENCE ?? "api://bank-integration",
    signingKeyPem: readPem(process.env.AUTH_SIGNING_KEY),
    keyId: process.env.AUTH_KEY_ID ?? "bank-sample-1",
    clients: parseClients(process.env.AUTH_CLIENTS),
    ttlSeconds: Number(process.env.AUTH_TOKEN_TTL ?? 300),
  });
  const port = Number(process.env.PORT ?? 8080);
  createServer(handler).listen(port, process.env.HOST ?? "::", () => console.log(JSON.stringify({ event: "started", service: "auth", port })));
}

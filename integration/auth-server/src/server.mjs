// OAuth 2.0 トークン発行サーバー（Microsoft Entra ID 互換のモック。RS256 の JWT アクセストークン）。
//
// 発行するトークン（形は Entra ID の v2.0 アクセストークンに合わせる：iss・aud・tid・oid・azp・ver・roles／scp・idtyp）
//  1. client_credentials（アプリのみ。連携先・連携コンソール・Agent のメーター）
//       → aud = AUTH_AUDIENCE（既定 api://bank-integration）、roles = クライアントに割り当てたロール、idtyp = "app"
//  2. セッションブリッジ（モック専用。Entra ID ではサインイン＝認可コード＋PKCE に置き換わる）
//       grant_type = urn:bank:params:oauth:grant-type:session-bridge
//       画面（Next.js のサーバー側）が、勘定系で確認したログイン中の行員について「Agent を呼ぶための委任トークン」を求める。
//       使えるのは AUTH_BRIDGE_CLIENTS のクライアントだけ → aud = api://bank-agent、scp = "Agent.Invoke"、oid・preferred_username = 行員
//  3. On-Behalf-Of（Entra ID と同じ要求の形）
//       grant_type = urn:ietf:params:oauth:grant-type:jwt-bearer、requested_token_use = on_behalf_of、assertion = 2 のトークン
//       Agent（中間層）が、受け取った行員のトークンを勘定系向けのトークンに交換する
//       → aud = api://bank-core、azp = Agent のクライアント ID（Agent の身元がトークンに結び付く）、oid・preferred_username = 行員、
//         scp = Agent に同意済みの委任スコープ（AUTH_OBO）
// 依存パッケージなし（Node.js 標準機能だけ）。

import { createServer } from "node:http";
import { createHash, createPrivateKey, createPublicKey, randomUUID, sign, timingSafeEqual, verify } from "node:crypto";
import { pathToFileURL } from "node:url";

const MAX_BODY = 16 * 1024;
export const GRANT_SESSION_BRIDGE = "urn:bank:params:oauth:grant-type:session-bridge";
export const GRANT_JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";
/** 行員のロール → トークンの roles（アプリロール名） */
const STAFF_ROLES = { TELLER: "Bank.Teller", SUPERVISOR: "Bank.Supervisor" };

/** base64url（パディングなし） */
export function b64url(buf) {
  return Buffer.from(buf).toString("base64").replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
}

/** 文字列から決まった形の UUID（v5 相当。Entra ID の oid の代わり。同じ利用者・テナントなら同じ値） */
export function stableUuid(...parts) {
  const h = createHash("sha256").update(parts.join("|")).digest("hex");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-5${h.slice(13, 16)}-${((parseInt(h[16], 16) & 0x3) | 0x8).toString(16)}${h.slice(17, 20)}-${h.slice(20, 32)}`;
}

/**
 * クライアント一覧を読む。形式：「client_id:sha256(secret) の 16 進:ロール+ロール」をカンマ区切り。
 * シークレットそのものはこのサービスに置かず、ハッシュだけを持つ（シークレットは 32 文字以上のランダム値）。
 */
export function parseClients(spec) {
  const clients = new Map();
  for (const entry of String(spec ?? "").split(",").map((s) => s.trim()).filter(Boolean)) {
    const [id, hash, roles = ""] = entry.split(":");
    if (!/^[A-Za-z0-9_.-]{3,64}$/.test(id ?? "") || !/^[0-9a-f]{64}$/.test(hash ?? "")) {
      throw new Error(`invalid AUTH_CLIENTS entry for ${id ?? "?"}`);
    }
    clients.set(id, { id, hash: Buffer.from(hash, "hex"), roles: roles.split("+").filter((r) => /^[A-Za-z0-9_.]{1,64}$/.test(r)) });
  }
  if (clients.size === 0) throw new Error("AUTH_CLIENTS has no clients");
  return clients;
}

/**
 * On-Behalf-Of の設定を読む。形式：「client_id>受け取る API>交換先の API:スコープ+スコープ」をカンマ区切り。
 * 例 bank-agent>api://bank-agent>api://bank-core:Accounts.Read+Transactions.Read
 * （client_id が公開する API 宛ての行員トークンだけを、交換先の API の指定スコープに交換できる）
 */
export function parseObo(spec) {
  const obo = new Map();
  for (const entry of String(spec ?? "").split(",").map((s) => s.trim()).filter(Boolean)) {
    const m = /^([A-Za-z0-9_.-]{3,64})>([^>\s]+)>([^>\s]+?):([A-Za-z0-9_.+]+)$/.exec(entry);
    if (!m) throw new Error(`invalid AUTH_OBO entry: ${entry}`);
    obo.set(m[1], { client: m[1], ownAudience: m[2], targetAudience: m[3], scopes: m[4].split("+").filter(Boolean) });
  }
  return obo;
}

/** PEM（そのまま、または Base64 で 1 行にしたもの）を読む */
export function readPem(value) {
  const v = String(value ?? "").trim();
  if (!v) throw new Error("AUTH_SIGNING_KEY is not set");
  return v.startsWith("-----BEGIN") ? v : Buffer.from(v, "base64").toString("utf8");
}

export function createAuthServer({
  issuer, audience, tenantId = "00000000-0000-0000-0000-000000000000", signingKeyPem, keyId, clients,
  bridgeClients = [], obo = new Map(), agentAudience = "api://bank-agent", ttlSeconds = 300, userTtlSeconds = 600,
  now = () => Date.now(), log = console.log,
}) {
  const privateKey = createPrivateKey(signingKeyPem);
  const publicKey = createPublicKey(privateKey);
  const jwk = { ...publicKey.export({ format: "jwk" }), kid: keyId, use: "sig", alg: "RS256" };
  const metrics = { issued: new Map(), failures: 0 };
  // client_id ごとの連続失敗（総当たり対策。Kong の IP ごとの制限に加える）
  const failures = new Map();

  function signJwt(payload) {
    const header = { alg: "RS256", typ: "JWT", kid: keyId };
    const input = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(payload))}`;
    return `${input}.${b64url(sign("sha256", Buffer.from(input), privateKey))}`;
  }

  function baseClaims(aud, ttl) {
    const iat = Math.floor(now() / 1000);
    return { iss: issuer, aud, tid: tenantId, ver: "2.0", iat, nbf: iat - 5, exp: iat + ttl, jti: randomUUID() };
  }

  // 自分が発行したトークンを検証する（On-Behalf-Of の assertion 用）
  function verifyOwn(token) {
    const parts = String(token ?? "").split(".");
    if (parts.length !== 3) throw new Error("malformed assertion");
    const header = JSON.parse(Buffer.from(parts[0], "base64url").toString("utf8"));
    if (header.alg !== "RS256" || header.kid !== keyId) throw new Error("unexpected key");
    if (!verify("sha256", Buffer.from(`${parts[0]}.${parts[1]}`), publicKey, Buffer.from(parts[2], "base64url"))) {
      throw new Error("bad signature");
    }
    const c = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"));
    const t = Math.floor(now() / 1000);
    if (c.iss !== issuer || c.tid !== tenantId) throw new Error("unexpected issuer or tenant");
    if (typeof c.exp !== "number" || c.exp < t || (c.nbf ?? 0) > t + 5) throw new Error("assertion expired");
    return c;
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

  function issued(res, clientId, token, ttl, scopeText, extraLog = {}) {
    metrics.issued.set(clientId, (metrics.issued.get(clientId) ?? 0) + 1);
    log(JSON.stringify({ event: "token-issued", clientId, ...extraLog }));
    res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store", Pragma: "no-cache" });
    res.end(JSON.stringify({ access_token: token, token_type: "Bearer", expires_in: ttl, scope: scopeText }));
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
    const grant = form.get("grant_type");
    if (![ "client_credentials", GRANT_SESSION_BRIDGE, GRANT_JWT_BEARER ].includes(grant)) {
      return oauthError(res, 400, "unsupported_grant_type", "unsupported grant_type");
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
      log(JSON.stringify({ event: "token-denied", clientId: String(id ?? "").slice(0, 64), grant }));
      return oauthError(res, 401, "invalid_client", "client authentication failed", { "WWW-Authenticate": 'Basic realm="bank-integration"' });
    }
    failures.delete(client.id);

    if (grant === GRANT_SESSION_BRIDGE) return sessionBridge(res, client, form);
    if (grant === GRANT_JWT_BEARER) return onBehalfOf(res, client, form);

    // client_credentials：scope は「<aud>/.default」（Entra ID と同じ書き方。割り当てられたロールすべて）か、ロール名の空白区切り
    const scope = (form.get("scope") ?? `${audience}/.default`).trim();
    let roles = client.roles;
    if (scope !== `${audience}/.default`) {
      const asked = scope.split(/\s+/).filter(Boolean);
      if (asked.length === 0 || asked.some((r) => !client.roles.includes(r))) {
        return oauthError(res, 400, "invalid_scope", "requested scope is not granted to this client");
      }
      roles = asked;
    }
    if (roles.length === 0) return oauthError(res, 400, "invalid_scope", "no application roles are assigned to this client");
    const claims = {
      ...baseClaims(audience, ttlSeconds), sub: stableUuid("sp", tenantId, client.id), oid: stableUuid("sp", tenantId, client.id),
      azp: client.id, appid: client.id, idtyp: "app", roles,
    };
    return issued(res, client.id, signJwt(claims), ttlSeconds, roles.join(" "), { grant, roles });
  }

  // モック専用：画面のサーバー側が、確認済みの行員について Agent 呼び出し用の委任トークンを得る
  function sessionBridge(res, client, form) {
    if (!bridgeClients.includes(client.id)) return oauthError(res, 400, "unauthorized_client", "this client may not use the session bridge");
    const subject = String(form.get("subject") ?? "");
    const role = String(form.get("subject_role") ?? "");
    if (!/^[A-Za-z0-9_.@-]{1,64}$/.test(subject) || !STAFF_ROLES[role]) return oauthError(res, 400, "invalid_request", "subject and subject_role are required");
    const scope = (form.get("scope") ?? `${agentAudience}/Agent.Invoke`).trim();
    if (scope !== `${agentAudience}/Agent.Invoke` && scope !== `${agentAudience}/.default`) {
      return oauthError(res, 400, "invalid_scope", "only Agent.Invoke can be requested");
    }
    const oid = stableUuid("user", tenantId, subject);
    const claims = {
      ...baseClaims(agentAudience, userTtlSeconds), sub: oid, oid, azp: client.id, preferred_username: subject, name: subject,
      scp: "Agent.Invoke", roles: [STAFF_ROLES[role]],
    };
    return issued(res, client.id, signJwt(claims), userTtlSeconds, "Agent.Invoke", { grant: "session-bridge", subject });
  }

  // On-Behalf-Of：中間層（Agent）が行員のトークンを勘定系向けのトークンに交換する
  function onBehalfOf(res, client, form) {
    if (form.get("requested_token_use") !== "on_behalf_of") return oauthError(res, 400, "invalid_request", "requested_token_use must be on_behalf_of");
    const policy = obo.get(client.id);
    if (!policy) return oauthError(res, 400, "unauthorized_client", "this client may not use on_behalf_of");
    let user;
    try {
      user = verifyOwn(form.get("assertion"));
    } catch (e) {
      log(JSON.stringify({ event: "obo-denied", clientId: client.id, reason: String(e.message) }));
      return oauthError(res, 400, "invalid_grant", "assertion is not valid");
    }
    // 受け取れるのは、この中間層が公開する API 宛ての「行員の」トークンだけ（アプリのみのトークンは交換できない）
    if (user.aud !== policy.ownAudience || user.idtyp === "app" || !user.oid || !String(user.scp ?? "").split(" ").includes("Agent.Invoke")) {
      return oauthError(res, 400, "invalid_grant", "assertion audience or type is not allowed");
    }
    const asked = (form.get("scope") ?? `${policy.targetAudience}/.default`).trim();
    let scopes = policy.scopes;
    if (asked !== `${policy.targetAudience}/.default`) {
      const list = asked.split(/\s+/).map((s) => s.startsWith(`${policy.targetAudience}/`) ? s.slice(policy.targetAudience.length + 1) : s);
      if (list.length === 0 || list.some((s) => !policy.scopes.includes(s))) return oauthError(res, 400, "invalid_scope", "scope is not consented for this client");
      scopes = list;
    }
    const claims = {
      ...baseClaims(policy.targetAudience, ttlSeconds), sub: user.sub, oid: user.oid, azp: client.id, appid: client.id,
      preferred_username: user.preferred_username, name: user.name, roles: user.roles ?? [], scp: scopes.join(" "),
    };
    return issued(res, client.id, signJwt(claims), ttlSeconds, scopes.join(" "), { grant: "on_behalf_of", subject: user.preferred_username, scopes });
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
      if (req.method === "POST" && (path === "/oauth2/token" || path === "/oauth2/v2.0/token")) return await token(req, res);
      if (req.method === "GET" && (path === "/.well-known/jwks.json" || path === "/discovery/v2.0/keys")) {
        return json(res, 200, { keys: [jwk] }, "public, max-age=300");
      }
      if (req.method === "GET" && path.endsWith("/.well-known/openid-configuration")) {
        return json(res, 200, {
          issuer, token_endpoint: `${issuer}/oauth2/token`, jwks_uri: `${issuer}/.well-known/jwks.json`,
          grant_types_supported: ["client_credentials", GRANT_JWT_BEARER], token_endpoint_auth_methods_supported: ["client_secret_basic", "client_secret_post"],
          id_token_signing_alg_values_supported: ["RS256"], tenant_id: tenantId,
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
    tenantId: process.env.AUTH_TENANT_ID ?? "00000000-0000-0000-0000-000000000000",
    signingKeyPem: readPem(process.env.AUTH_SIGNING_KEY),
    keyId: process.env.AUTH_KEY_ID ?? "bank-sample-1",
    clients: parseClients(process.env.AUTH_CLIENTS),
    bridgeClients: String(process.env.AUTH_BRIDGE_CLIENTS ?? "").split(",").map((s) => s.trim()).filter(Boolean),
    obo: parseObo(process.env.AUTH_OBO),
    agentAudience: process.env.AUTH_AGENT_AUDIENCE ?? "api://bank-agent",
    ttlSeconds: Number(process.env.AUTH_TOKEN_TTL ?? 300),
  });
  const port = Number(process.env.PORT ?? 8080);
  createServer(handler).listen(port, process.env.HOST ?? "::", () => console.log(JSON.stringify({ event: "started", service: "auth", port })));
}

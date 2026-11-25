// 認証まわり：受け取ったトークンの検証（JWKS・RS256）と、On-Behalf-Of・クライアントクレデンシャルでのトークン取得。
// Microsoft Entra ID と自前の auth（Entra 互換のモック）のどちらでも同じ手順で動く（URL と発行者を設定で切り替える）。

import { createHash, createPublicKey, verify } from "node:crypto";

export class AuthError extends Error {
  constructor(message, status = 401) { super(message); this.status = status; }
}

/** JWKS から公開鍵を引く（5 分キャッシュ。知らない kid なら取り直す） */
export function createJwks(url, fetchImpl = fetch, now = () => Date.now()) {
  let keys = new Map();
  let fetchedAt = 0;
  async function load() {
    const res = await fetchImpl(url, { signal: AbortSignal.timeout(5000) });
    if (!res.ok) throw new AuthError(`JWKS を取得できません（HTTP ${res.status}）`, 503);
    const body = await res.json();
    keys = new Map((body.keys ?? []).filter((k) => k.kty === "RSA" && k.kid).map((k) => [k.kid, createPublicKey({ key: k, format: "jwk" })]));
    fetchedAt = now();
  }
  return async function keyFor(kid) {
    if (!keys.has(kid) || now() - fetchedAt > 300_000) await load();
    const k = keys.get(kid);
    if (!k) throw new AuthError("unknown signing key");
    return k;
  };
}

/**
 * アクセストークンを検証して claims を返す。署名（RS256・JWKS の kid）・iss・aud・tid（許可リスト）・exp・nbf・種類（委任）・スコープ。
 * Kong も同じ検証をしているが、Agent でも独立に確かめる（ネットワークの位置だけを信用しない）。
 */
export async function verifyUserToken(token, { keyFor, issuer, audience, tenants, requiredScope, now = () => Date.now() }) {
  const parts = String(token ?? "").split(".");
  if (parts.length !== 3) throw new AuthError("malformed token");
  let header;
  let claims;
  try {
    header = JSON.parse(Buffer.from(parts[0], "base64url").toString("utf8"));
    claims = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"));
  } catch {
    throw new AuthError("malformed token");
  }
  if (header.alg !== "RS256" || typeof header.kid !== "string") throw new AuthError("unexpected algorithm");
  const key = await keyFor(header.kid);
  if (!verify("sha256", Buffer.from(`${parts[0]}.${parts[1]}`), key, Buffer.from(parts[2], "base64url"))) throw new AuthError("bad signature");
  const t = Math.floor(now() / 1000);
  if (claims.iss !== issuer) throw new AuthError("unexpected issuer");
  const aud = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
  if (!aud.includes(audience)) throw new AuthError("unexpected audience");
  if (tenants.length > 0 && !tenants.includes(claims.tid)) throw new AuthError("tenant not allowed", 403);
  if (typeof claims.exp !== "number" || claims.exp < t || (claims.nbf ?? 0) > t + 30) throw new AuthError("token expired");
  if (claims.idtyp === "app" || !claims.oid || typeof claims.scp !== "string") throw new AuthError("a delegated (user) token is required", 403);
  if (!claims.scp.split(" ").includes(requiredScope)) throw new AuthError("missing scope", 403);
  if (!/^[A-Za-z0-9_.@-]{1,64}$/.test(String(claims.preferred_username ?? ""))) throw new AuthError("missing user", 403);
  return claims;
}

export const sha256 = (s) => createHash("sha256").update(s).digest("hex");

/** トークン取得（On-Behalf-Of とクライアントクレデンシャル）。アプリのトークンは期限の 30 秒前まで再利用する */
export function createTokenClient({ tokenUrl, clientId, clientSecret, fetchImpl = fetch, now = () => Date.now() }) {
  const basic = `Basic ${Buffer.from(`${encodeURIComponent(clientId)}:${encodeURIComponent(clientSecret)}`).toString("base64")}`;
  let appToken = null;

  async function request(form) {
    const res = await fetchImpl(tokenUrl, {
      method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded", Authorization: basic },
      body: new URLSearchParams(form).toString(), signal: AbortSignal.timeout(10_000),
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok || !body.access_token) {
      throw new AuthError(`token endpoint: ${body.error ?? `HTTP ${res.status}`}`, res.status >= 500 ? 503 : 403);
    }
    return body;
  }

  return {
    /** 行員のトークンを勘定系向けに交換する（Entra ID と同じ要求の形） */
    async onBehalfOf(userToken, scope) {
      const b = await request({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", requested_token_use: "on_behalf_of", assertion: userToken, scope,
      });
      return { token: b.access_token, scopes: String(b.scope ?? "").split(" ").filter(Boolean) };
    },
    /** Agent 自身のトークン（メーター用） */
    async appToken(scope) {
      if (appToken && appToken.expiresAt > now()) return appToken.token;
      const b = await request({ grant_type: "client_credentials", ...(scope ? { scope } : {}) });
      appToken = { token: b.access_token, expiresAt: now() + Math.max(0, (b.expires_in ?? 60) - 30) * 1000 };
      return appToken.token;
    },
  };
}

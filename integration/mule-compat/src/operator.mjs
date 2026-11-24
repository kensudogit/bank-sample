// 連携コンソールの行員 ID（作成者・承認者）の署名付きトークン（HS256 の JWT）を検証する。
// 連携コンソール（Next.js のサーバー側）が OPERATOR_TOKEN_SECRET で署名し、X-Bank-Operator-Token で送る。
// 平文のヘッダー（X-Bank-Operator）は信用しない（Kong も取り除く）。署名・期限・宛先に加えて、
// 本文の SHA-256 がトークンの bodySha256 と一致することを確かめ、承認された内容と送られた内容の差し替えを防ぐ。

import { createHmac, timingSafeEqual } from "node:crypto";

export const OPERATOR_ISSUER = "bank-console";
export const OPERATOR_AUDIENCE = "bank-integration";

const b64url = (buf) => Buffer.from(buf).toString("base64").replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");

/** 署名する（テストと、連携コンソールの実装の参照用。frontend/lib/integration-server.ts と同じ形式） */
export function signOperatorToken(claims, secret, nowSec = Math.floor(Date.now() / 1000)) {
  const header = b64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const payload = b64url(JSON.stringify({ iss: OPERATOR_ISSUER, aud: OPERATOR_AUDIENCE, iat: nowSec, exp: nowSec + 120, ...claims }));
  const sig = b64url(createHmac("sha256", secret).update(`${header}.${payload}`).digest());
  return `${header}.${payload}.${sig}`;
}

/**
 * 検証して claims を返す。不正なら理由付きの例外。
 * @param {string} token X-Bank-Operator-Token の値
 * @param {string} secret 共有鍵（32 文字以上）
 * @param {string} bodySha256 受け取った本文の SHA-256（16 進）
 */
export function verifyOperatorToken(token, secret, bodySha256, nowSec = Math.floor(Date.now() / 1000)) {
  if (!secret || secret.length < 32) throw new Error("operator token secret is not configured");
  const parts = String(token ?? "").split(".");
  if (parts.length !== 3) throw new Error("malformed operator token");
  const [h, p, s] = parts;
  const header = JSON.parse(Buffer.from(h, "base64url").toString("utf8"));
  if (header.alg !== "HS256") throw new Error("unexpected algorithm");
  const expected = createHmac("sha256", secret).update(`${h}.${p}`).digest();
  const actual = Buffer.from(s, "base64url");
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) throw new Error("bad signature");
  const claims = JSON.parse(Buffer.from(p, "base64url").toString("utf8"));
  if (claims.iss !== OPERATOR_ISSUER || claims.aud !== OPERATOR_AUDIENCE) throw new Error("unexpected issuer or audience");
  if (typeof claims.exp !== "number" || claims.exp < nowSec - 5) throw new Error("operator token expired");
  if (typeof claims.iat !== "number" || claims.iat > nowSec + 30) throw new Error("operator token issued in the future");
  if (claims.bodySha256 !== bodySha256) throw new Error("body does not match the approved payload");
  for (const k of ["maker", "approver", "approvalId"]) {
    if (typeof claims[k] !== "string" || !/^[A-Za-z0-9_.@-]{1,64}$/.test(claims[k])) throw new Error(`invalid ${k}`);
  }
  if (claims.maker === claims.approver) throw new Error("maker and approver must differ");
  return claims;
}

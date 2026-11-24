// 連携コンソール（/integration）のサーバー側処理。ブラウザ → Next.js（このファイル）→ Kong → MuleSoft の順に中継する。
// - 送信は二者承認：作成した役席とは別の役席が承認したときだけ、Next.js のサーバー側が Kong に送る（勘定系の DB が作成者・承認者・
//   内容のハッシュを保持し、作成者本人の承認を拒否する）
// - Kong へは OAuth 2.0 のアクセストークン（クライアントクレデンシャル。bank-ops）で送る。シークレットもトークンもブラウザに渡さない
// - 作成者・承認者は署名付きトークン（X-Bank-Operator-Token。HS256・2 分有効・本文の SHA-256 入り）で MuleSoft に伝える
// - CSRF 対策：X-Requested-With、Origin の完全一致（PUBLIC_ORIGIN）、セッションに結び付いた CSRF トークン（X-CSRF-Token）
// - 呼び出せるのは役席だけ。ブラウザの Cookie で勘定系の /me を呼び、ログイン中の利用者とロールを確かめる

import { createHash, createHmac, timingSafeEqual } from "node:crypto";

/** 送信できるデータの種類 → 勘定系の種類・Kong のパス・Content-Type */
export const SUBMIT_TARGETS = {
  zengin: { kind: "ZENGIN_INCOMING", path: "/zengin/incoming", types: ["text/plain"] },
  billing: { kind: "BILLING_IMPORT", path: "/billing/import", types: ["text/csv", "text/plain"] },
  fx: { kind: "FX_RATES", path: "/fx/rates", types: ["application/json"] },
} as const;
export type SubmitKind = keyof typeof SUBMIT_TARGETS;

/** 勘定系の種類 → 画面の種類 */
export const KIND_BY_CORE: Record<string, SubmitKind> = { ZENGIN_INCOMING: "zengin", BILLING_IMPORT: "billing", FX_RATES: "fx" };

/** 1 回の送信の上限（Kong 側の上限 5 MB に合わせる） */
export const MAX_UPLOAD = 5 * 1024 * 1024;
/** 勘定系のセッション Cookie 名（Liberty の server.xml） */
export const SESSION_COOKIE = "BANKSESSION";

export interface IntegrationConfig {
  /** 勘定系 API（Kong 経由・Host: kong）。例 http://kong:8000/bank/api */
  coreApi: string;
  /** Kong の外部連携 API。例 http://kong:8000/integration/v1 */
  gateway: string;
  /** OAuth 2.0 のトークン発行（Kong 経由）。例 http://kong:8000/oauth2/token */
  tokenUrl: string;
  clientId: string;
  clientSecret: string;
  /** トークンの scope（Entra ID では「<Application ID URI>/.default」。自前の auth では省略できる） */
  tokenScope?: string;
  /** 行員 ID の署名鍵（MuleSoft と共有） */
  operatorSecret: string;
  /** CSRF トークンの鍵 */
  csrfSecret: string;
  /** 更新要求を受け付ける Origin（完全一致）。未設定なら要求の Host から作る */
  publicOrigins: string[];
}

export function configFromEnv(env: Record<string, string | undefined> = process.env): IntegrationConfig | null {
  const coreApi = env.BANK_API_INTERNAL;
  const gateway = env.INTEGRATION_GATEWAY_URL;
  const tokenUrl = env.INTEGRATION_TOKEN_URL;
  const clientId = env.INTEGRATION_CLIENT_ID;
  const clientSecret = env.INTEGRATION_CLIENT_SECRET;
  const operatorSecret = env.OPERATOR_TOKEN_SECRET ?? "";
  const csrfSecret = env.CONSOLE_CSRF_SECRET ?? "";
  if (!coreApi || !gateway || !tokenUrl || !clientId || !clientSecret || operatorSecret.length < 32 || csrfSecret.length < 32) return null;
  const publicOrigins = (env.PUBLIC_ORIGIN ?? "").split(",").map((s) => s.trim().replace(/\/$/, "")).filter(Boolean);
  const tokenScope = env.INTEGRATION_TOKEN_SCOPE || undefined;
  return { coreApi, gateway, tokenUrl, clientId, clientSecret, tokenScope, operatorSecret, csrfSecret, publicOrigins };
}

/** RFC 9457 形式のエラー応答 */
export function problem(status: number, title: string, detail?: string): Response {
  return new Response(JSON.stringify({ type: "about:blank", title, status, detail }), {
    status,
    headers: { "Content-Type": "application/problem+json", "Cache-Control": "no-store" },
  });
}

/** JSON の成功応答（キャッシュさせない） */
export function ok(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
}

/** ブラウザの Cookie で勘定系の /me を呼び、役席なら利用者 ID を返す（それ以外は拒否の Response） */
export async function requireSupervisor(req: Request, cfg: IntegrationConfig, fetchImpl: typeof fetch = fetch): Promise<string | Response> {
  const cookie = req.headers.get("cookie");
  if (!cookie) return problem(401, "ログインしてください");
  let res: Response;
  try {
    res = await fetchImpl(`${cfg.coreApi}/me`, { headers: { Cookie: cookie, Accept: "application/json" }, cache: "no-store" });
  } catch {
    return problem(502, "勘定系に接続できません");
  }
  const json = (await res.json().catch(() => null)) as { data?: { userId: string; role: string; mustChangePassword: boolean } | null } | null;
  const user = json?.data;
  if (!res.ok || !user) return problem(401, "ログインしてください");
  if (user.role !== "SUPERVISOR" || user.mustChangePassword) return problem(403, "この操作を行う権限がありません");
  return user.userId;
}

// ---------------- CSRF ----------------

/** Cookie ヘッダーから勘定系のセッション ID を取り出す */
export function sessionId(req: Request): string | null {
  const cookie = req.headers.get("cookie") ?? "";
  for (const part of cookie.split(";")) {
    const [k, ...v] = part.trim().split("=");
    if (k === SESSION_COOKIE && v.length) return v.join("=");
  }
  return null;
}

/** セッションに結び付いた CSRF トークン（セッションが変われば無効になる。サーバーに状態を持たない） */
export function csrfTokenFor(session: string, secret: string): string {
  return createHmac("sha256", secret).update(`csrf:${session}`).digest("base64url");
}

/** 許可する Origin（設定がなければ、要求の Host と X-Forwarded-Proto から作る） */
function allowedOrigins(req: Request, cfg: IntegrationConfig): string[] {
  if (cfg.publicOrigins.length) return cfg.publicOrigins;
  const host = req.headers.get("x-forwarded-host") ?? req.headers.get("host");
  const proto = req.headers.get("x-forwarded-proto") ?? new URL(req.url).protocol.replace(":", "");
  return host ? [`${proto}://${host}`] : [];
}

const safeEqual = (a: string, b: string) => {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
};

/**
 * 更新要求の CSRF 対策：
 *  1. X-Requested-With: XMLHttpRequest（別オリジンからは事前確認なしに付けられない）
 *  2. Origin が許可した値と完全一致（スキーム・ホスト・ポート。Origin なしは拒否）
 *  3. X-CSRF-Token がセッションから計算した値と一致（同期トークン）
 */
export function checkCsrf(req: Request, cfg: IntegrationConfig): Response | null {
  if (req.headers.get("x-requested-with") !== "XMLHttpRequest") return problem(403, "不正な要求です");
  const origin = req.headers.get("origin");
  if (!origin || !allowedOrigins(req, cfg).includes(origin.replace(/\/$/, ""))) return problem(403, "不正な要求です", "送信元を確認できません。");
  const session = sessionId(req);
  const token = req.headers.get("x-csrf-token");
  if (!session || !token || !safeEqual(token, csrfTokenFor(session, cfg.csrfSecret))) {
    return problem(403, "不正な要求です", "画面を再読み込みしてからやり直してください。");
  }
  return null;
}

// ---------------- OAuth 2.0（クライアントクレデンシャル） ----------------

const tokenCache = new Map<string, { token: string; expiresAt: number }>();

/** テスト用：トークンのキャッシュを消す */
export function clearTokenCache(): void {
  tokenCache.clear();
}

/** アクセストークンを取る（期限の 30 秒前まで再利用する） */
export async function accessToken(cfg: IntegrationConfig, fetchImpl: typeof fetch = fetch, now = Date.now()): Promise<string> {
  const cached = tokenCache.get(cfg.clientId);
  if (cached && cached.expiresAt > now) return cached.token;
  const res = await fetchImpl(cfg.tokenUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      Authorization: `Basic ${Buffer.from(`${encodeURIComponent(cfg.clientId)}:${encodeURIComponent(cfg.clientSecret)}`).toString("base64")}`,
    },
    body: cfg.tokenScope ? `grant_type=client_credentials&scope=${encodeURIComponent(cfg.tokenScope)}` : "grant_type=client_credentials",
    cache: "no-store",
  });
  const json = (await res.json().catch(() => null)) as { access_token?: string; expires_in?: number } | null;
  if (!res.ok || !json?.access_token) throw new Error(`token endpoint returned HTTP ${res.status}`);
  tokenCache.set(cfg.clientId, { token: json.access_token, expiresAt: now + Math.max(0, (json.expires_in ?? 60) - 30) * 1000 });
  return json.access_token;
}

// ---------------- 行員 ID の署名 ----------------

const b64url = (s: string | Buffer) => Buffer.from(s).toString("base64url");

/** 作成者・承認者・依頼 ID・本文のハッシュを HS256 で署名する（2 分有効。MuleSoft が検証する） */
export function signOperatorToken(
  claims: { maker: string; approver: string; approvalId: string; bodySha256: string }, secret: string, nowSec = Math.floor(Date.now() / 1000),
): string {
  const header = b64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const payload = b64url(JSON.stringify({ iss: "bank-console", aud: "bank-integration", iat: nowSec, exp: nowSec + 120, ...claims }));
  const sig = createHmac("sha256", secret).update(`${header}.${payload}`).digest("base64url");
  return `${header}.${payload}.${sig}`;
}

export const sha256Hex = (buf: Uint8Array) => createHash("sha256").update(buf).digest("hex");

// ---------------- 勘定系・Kong の呼び出し ----------------

/** 勘定系の API をブラウザの Cookie（＝ログイン中の役席）で呼ぶ。勘定系の {data} / {error} をそのまま返す */
export async function coreCall(
  req: Request, cfg: IntegrationConfig, path: string, body?: unknown, fetchImpl: typeof fetch = fetch,
): Promise<{ status: number; data?: any; error?: { code: string; message: string } }> {
  const headers: Record<string, string> = { Cookie: req.headers.get("cookie") ?? "", Accept: "application/json" };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    headers["X-Requested-With"] = "XMLHttpRequest";
  }
  let res: Response;
  try {
    res = await fetchImpl(`${cfg.coreApi}${path}`, {
      method: body === undefined ? "GET" : "POST", headers, body: body === undefined ? undefined : JSON.stringify(body), cache: "no-store",
    });
  } catch {
    return { status: 502, error: { code: "CORE_UNAVAILABLE", message: "勘定系に接続できません" } };
  }
  const json = (await res.json().catch(() => null)) as { data?: unknown; error?: { code: string; message: string } } | null;
  if (res.ok && json && !json.error) return { status: res.status, data: json.data };
  return { status: res.status, error: json?.error ?? { code: "SYSTEM_ERROR", message: `勘定系でエラーが発生しました（HTTP ${res.status}）` } };
}

/** 勘定系のエラーを problem+json にする */
export const coreProblem = (r: { status: number; error?: { message: string } }) =>
  problem(r.status >= 400 ? r.status : 502, r.error?.message ?? "エラーが発生しました");

/** Kong へ中継する（アクセストークン付き。401 ならトークンを取り直して 1 回だけ再送）。応答はそのまま返す */
export async function forward(
  cfg: IntegrationConfig, path: string,
  init: { method: string; body?: BodyInit; contentType?: string; operatorToken?: string; idempotencyKey?: string },
  fetchImpl: typeof fetch = fetch,
): Promise<Response> {
  const send = async () => {
    const headers: Record<string, string> = { Authorization: `Bearer ${await accessToken(cfg, fetchImpl)}`, Accept: "application/json" };
    if (init.contentType) headers["Content-Type"] = init.contentType;
    if (init.operatorToken) headers["X-Bank-Operator-Token"] = init.operatorToken;
    if (init.idempotencyKey) headers["Idempotency-Key"] = init.idempotencyKey;
    return fetchImpl(`${cfg.gateway}${path}`, { method: init.method, body: init.body, headers, cache: "no-store" });
  };
  let res: Response;
  try {
    res = await send();
    if (res.status === 401) {
      clearTokenCache();
      res = await send();
    }
  } catch {
    return problem(502, "API ゲートウェイ（Kong）に接続できません");
  }
  const text = await res.text();
  return new Response(text, {
    status: res.status,
    headers: {
      "Content-Type": res.headers.get("content-type") ?? "application/json",
      "X-Request-ID": res.headers.get("x-request-id") ?? "",
      "Cache-Control": "no-store",
    },
  });
}

/**
 * 承認済みの依頼を Kong に送り、結果を勘定系に記録して返す。
 * 依頼の本文が承認時のハッシュと一致することを確かめ、作成者・承認者・依頼 ID・ハッシュを署名して MuleSoft に伝える。
 */
export async function dispatchApproved(
  req: Request, cfg: IntegrationConfig,
  view: { requestId: string; kind: string; contentType: string; payloadBase64: string; payloadSha256: string; maker: string; approver: string },
  fetchImpl: typeof fetch = fetch,
): Promise<Response> {
  const kind = KIND_BY_CORE[view.kind];
  if (!kind) return problem(500, "依頼の種類が不正です");
  const bytes = Buffer.from(view.payloadBase64, "base64");
  if (sha256Hex(bytes) !== view.payloadSha256) return problem(409, "依頼の内容が承認時と一致しません");
  const operatorToken = signOperatorToken(
    { maker: view.maker, approver: view.approver, approvalId: view.requestId, bodySha256: view.payloadSha256 }, cfg.operatorSecret);
  const res = await forward(cfg, SUBMIT_TARGETS[kind].path, {
    method: "POST", body: bytes, contentType: view.contentType, operatorToken, idempotencyKey: `approval-${view.requestId}`,
  }, fetchImpl);
  const text = await res.text();
  let result: any = null;
  try { result = JSON.parse(text); } catch { /* JSON でない応答 */ }
  // 接続できなかった（Kong の 502 は problem で返る）・5xx は FAILED として記録し、再送できるようにする
  const recorded = await coreCall(req, cfg, "/integration-request-complete", {
    requestId: view.requestId, runId: typeof result?.runId === "string" ? result.runId : null,
    httpStatus: String(res.status), resultBody: text.slice(0, 1_000_000),
  }, fetchImpl);
  return ok({ request: recorded.data ?? null, httpStatus: res.status, result, kind }, 200);
}

/** 送信の Content-Type を許可された種類に正規化する（charset は残す）。許可されなければ null */
export function acceptedContentType(kind: SubmitKind, contentType: string | null): string | null {
  const ct = (contentType ?? "").toLowerCase();
  const base = ct.split(";")[0].trim();
  if (!(SUBMIT_TARGETS[kind].types as readonly string[]).includes(base)) return null;
  const charset = /charset\s*=\s*"?([\w-]+)/i.exec(ct)?.[1];
  return charset ? `${base}; charset=${charset}` : base;
}

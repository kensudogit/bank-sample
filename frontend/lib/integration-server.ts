// 連携コンソール（/integration）のサーバー側処理。ブラウザ → Next.js（このファイル）→ Kong → MuleSoft の順に中継する。
// - Kong の API キー（bank-ops）はサーバーの環境変数だけに置き、ブラウザには渡さない
// - 呼び出せるのは役席だけ。ブラウザの Cookie で勘定系の /me を呼び、ログイン中の利用者とロールを確かめる
// - 更新（送信）は CSRF 対策として X-Requested-With: XMLHttpRequest と同一オリジンを必須にする
// - 行員の利用者 ID を X-Bank-Operator で MuleSoft に伝え、実行履歴に残す（Kong の利用者が bank-ops の場合だけ信用される）

/** 送信できるデータの種類 → Kong のパスと Content-Type */
export const SUBMIT_TARGETS = {
  zengin: { path: "/zengin/incoming", types: ["text/plain"] },
  billing: { path: "/billing/import", types: ["text/csv", "text/plain"] },
  fx: { path: "/fx/rates", types: ["application/json"] },
} as const;
export type SubmitKind = keyof typeof SUBMIT_TARGETS;

/** 1 回の送信の上限（Kong 側の上限 5 MB に合わせる） */
export const MAX_UPLOAD = 5 * 1024 * 1024;

export interface IntegrationConfig {
  /** 勘定系 API（Kong 経由・Host: kong）。例 http://kong:8000/bank/api */
  coreApi: string;
  /** Kong の外部連携 API。例 http://kong:8000/integration/v1 */
  gateway: string;
  /** Kong の bank-ops 利用者の API キー */
  opsKey: string;
}

export function configFromEnv(env: Record<string, string | undefined> = process.env): IntegrationConfig | null {
  const coreApi = env.BANK_API_INTERNAL;
  const gateway = env.INTEGRATION_GATEWAY_URL;
  const opsKey = env.INTEGRATION_OPS_KEY;
  if (!coreApi || !gateway || !opsKey) return null;
  return { coreApi, gateway, opsKey };
}

/** RFC 9457 形式のエラー応答 */
export function problem(status: number, title: string, detail?: string): Response {
  return new Response(JSON.stringify({ type: "about:blank", title, status, detail }), {
    status,
    headers: { "Content-Type": "application/problem+json", "Cache-Control": "no-store" },
  });
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

/**
 * 更新要求の CSRF 対策：XHR ヘッダー（別オリジンからは事前確認なしに付けられない）と、Origin があれば同じホスト名であること。
 * ホスト名だけで比べる（前段の nginx は Host をポートなしで転送するため。ポート違いは同一サイトで Cookie も SameSite=Lax）。
 */
export function checkCsrf(req: Request): Response | null {
  if (req.headers.get("x-requested-with") !== "XMLHttpRequest") return problem(403, "不正な要求です");
  const origin = req.headers.get("origin");
  const host = req.headers.get("x-forwarded-host") ?? req.headers.get("host");
  if (origin && host) {
    let originHost: string;
    try {
      originHost = new URL(origin).hostname;
    } catch {
      return problem(403, "不正な要求です");
    }
    const hostName = host.replace(/:\d+$/, "").replace(/^\[|\]$/g, "");
    if (originHost.replace(/^\[|\]$/g, "") !== hostName) return problem(403, "不正な要求です");
  }
  return null;
}

/** Kong へ中継し、応答（JSON / problem+json）をそのまま返す */
export async function forward(
  cfg: IntegrationConfig, path: string, init: { method: string; body?: BodyInit; contentType?: string; operator: string },
  fetchImpl: typeof fetch = fetch,
): Promise<Response> {
  const headers: Record<string, string> = { apikey: cfg.opsKey, "X-Bank-Operator": init.operator, Accept: "application/json" };
  if (init.contentType) headers["Content-Type"] = init.contentType;
  let res: Response;
  try {
    res = await fetchImpl(`${cfg.gateway}${path}`, { method: init.method, body: init.body, headers, cache: "no-store" });
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

/** 送信の Content-Type を許可された種類に正規化する（charset は残す）。許可されなければ null */
export function acceptedContentType(kind: SubmitKind, contentType: string | null): string | null {
  const ct = (contentType ?? "").toLowerCase();
  const base = ct.split(";")[0].trim();
  if (!(SUBMIT_TARGETS[kind].types as readonly string[]).includes(base)) return null;
  const charset = /charset\s*=\s*"?([\w-]+)/i.exec(ct)?.[1];
  return charset ? `${base}; charset=${charset}` : base;
}

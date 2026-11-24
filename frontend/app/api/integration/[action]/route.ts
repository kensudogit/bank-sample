// 連携コンソールのサーバー側 API（/api/integration/status・runs・csrf）。
// 役席のログインを確かめてから、Kong の外部連携 API（bank-ops のアクセストークン）へ中継する。処理の本体は lib/integration-server.ts。
// 送信（二者承認）は /api/integration/requests 以下。

import { configFromEnv, csrfTokenFor, forward, ok, problem, requireSupervisor, sessionId } from "@/lib/integration-server";

export const dynamic = "force-dynamic";

type Ctx = { params: Promise<{ action: string }> };

/** 稼働状況（status）・実行履歴（runs）・CSRF トークン（csrf） */
export async function GET(req: Request, ctx: Ctx): Promise<Response> {
  const { action } = await ctx.params;
  const cfg = configFromEnv();
  if (!cfg) {
    return problem(503, "外部連携が設定されていません",
      "INTEGRATION_GATEWAY_URL・INTEGRATION_TOKEN_URL・INTEGRATION_CLIENT_ID/SECRET・OPERATOR_TOKEN_SECRET・CONSOLE_CSRF_SECRET を設定してください。");
  }
  const user = await requireSupervisor(req, cfg);
  if (typeof user !== "string") return user;
  if (action === "status") return forward(cfg, "/health", { method: "GET" });
  if (action === "runs") return forward(cfg, "/runs", { method: "GET" });
  if (action === "csrf") {
    const session = sessionId(req);
    if (!session) return problem(401, "ログインしてください");
    return ok({ token: csrfTokenFor(session, cfg.csrfSecret) });
  }
  return problem(404, "見つかりません");
}

/** 直接の送信は廃止（二者承認を経ずに送らせない） */
export async function POST(): Promise<Response> {
  return problem(405, "直接の送信はできません", "承認依頼を作成し、別の役席の承認を受けてください。");
}

// 連携コンソールのサーバー側 API（/api/integration/status・runs・zengin・billing・fx）。
// 役席のログインを確かめてから、Kong の外部連携 API（bank-ops の API キー）へ中継する。処理の本体は lib/integration-server.ts。

import {
  MAX_UPLOAD, SUBMIT_TARGETS, acceptedContentType, checkCsrf, configFromEnv, forward, problem, requireSupervisor, type SubmitKind,
} from "@/lib/integration-server";

export const dynamic = "force-dynamic";

type Ctx = { params: Promise<{ action: string }> };

/** 稼働状況（status）と実行履歴（runs）の取得 */
export async function GET(req: Request, ctx: Ctx): Promise<Response> {
  const { action } = await ctx.params;
  const cfg = configFromEnv();
  if (!cfg) return problem(503, "外部連携が設定されていません", "INTEGRATION_GATEWAY_URL・INTEGRATION_OPS_KEY を設定してください。");
  const user = await requireSupervisor(req, cfg);
  if (typeof user !== "string") return user;
  if (action === "status") return forward(cfg, "/health", { method: "GET", operator: user });
  if (action === "runs") return forward(cfg, "/runs", { method: "GET", operator: user });
  return problem(404, "見つかりません");
}

/** データの送信（zengin・billing・fx） */
export async function POST(req: Request, ctx: Ctx): Promise<Response> {
  const { action } = await ctx.params;
  if (!(action in SUBMIT_TARGETS)) return problem(404, "見つかりません");
  const kind = action as SubmitKind;
  const csrf = checkCsrf(req);
  if (csrf) return csrf;
  const cfg = configFromEnv();
  if (!cfg) return problem(503, "外部連携が設定されていません");
  const user = await requireSupervisor(req, cfg);
  if (typeof user !== "string") return user;
  const contentType = acceptedContentType(kind, req.headers.get("content-type"));
  if (!contentType) return problem(415, "この形式のデータは送信できません");
  const body = await req.arrayBuffer();
  if (body.byteLength === 0) return problem(422, "データが空です");
  if (body.byteLength > MAX_UPLOAD) return problem(413, "データが大きすぎます（5 MB まで）");
  return forward(cfg, SUBMIT_TARGETS[kind].path, { method: "POST", body, contentType, operator: user });
}

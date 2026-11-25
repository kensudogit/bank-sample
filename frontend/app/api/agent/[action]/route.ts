// AI アシスタントのサーバー側 API：GET /api/agent/csrf（CSRF トークン）、POST /api/agent/runs（質問）。
// 窓口・役席だけ。処理の本体は lib/agent-server.ts。

import { agentConfigFromEnv, requireStaff, runAgent } from "@/lib/agent-server";
import { checkCsrf, csrfTokenFor, ok, problem, sessionId, type IntegrationConfig } from "@/lib/integration-server";

export const dynamic = "force-dynamic";

type Ctx = { params: Promise<{ action: string }> };

// CSRF の確認は連携コンソールと同じ関数を使う（鍵と許可する Origin だけを渡す）
const csrfConfig = (cfg: { csrfSecret: string; publicOrigins: string[] }) => cfg as unknown as IntegrationConfig;

export async function GET(req: Request, ctx: Ctx): Promise<Response> {
  const { action } = await ctx.params;
  if (action !== "csrf") return problem(404, "見つかりません");
  const cfg = agentConfigFromEnv();
  if (!cfg) return problem(503, "AI アシスタントが設定されていません");
  const user = await requireStaff(req, cfg);
  if (user instanceof Response) return user;
  const session = sessionId(req);
  if (!session) return problem(401, "ログインしてください");
  return ok({ token: csrfTokenFor(session, cfg.csrfSecret) });
}

export async function POST(req: Request, ctx: Ctx): Promise<Response> {
  const { action } = await ctx.params;
  if (action !== "runs") return problem(404, "見つかりません");
  const cfg = agentConfigFromEnv();
  if (!cfg) return problem(503, "AI アシスタントが設定されていません", "AGENT_RUNS_URL・AGENT_CONSOLE_CLIENT_ID/SECRET を設定してください。");
  const csrf = checkCsrf(req, csrfConfig(cfg));
  if (csrf) return csrf;
  const user = await requireStaff(req, cfg);
  if (user instanceof Response) return user;
  const body = (await req.json().catch(() => null)) as { prompt?: unknown } | null;
  const prompt = typeof body?.prompt === "string" ? body.prompt.trim() : "";
  if (!prompt || prompt.length > 2000) return problem(422, "質問は 1〜2000 文字で入力してください");
  return runAgent(user, prompt, cfg);
}

// 手動送信の依頼の操作：approve（承認して送信）・reject（却下）・retry（失敗した送信の再送）。
// 承認・却下は作成者以外の役席だけ（勘定系が拒否する）。送信は承認された内容（ハッシュ一致）だけを、署名した行員 ID とともに送る。

import { checkCsrf, configFromEnv, coreCall, coreProblem, dispatchApproved, ok, problem, requireSupervisor } from "@/lib/integration-server";

export const dynamic = "force-dynamic";

type Ctx = { params: Promise<{ id: string; op: string }> };

export async function POST(req: Request, ctx: Ctx): Promise<Response> {
  const { id, op } = await ctx.params;
  if (!/^[A-Za-z0-9-]{1,36}$/.test(id) || !["approve", "reject", "retry"].includes(op)) return problem(404, "見つかりません");
  const cfg = configFromEnv();
  if (!cfg) return problem(503, "外部連携が設定されていません");
  const csrf = checkCsrf(req, cfg);
  if (csrf) return csrf;
  const user = await requireSupervisor(req, cfg);
  if (typeof user !== "string") return user;

  if (op === "reject") {
    const body = (await req.json().catch(() => null)) as { reason?: string } | null;
    const r = await coreCall(req, cfg, "/integration-request-reject", { requestId: id, reason: body?.reason ?? "" });
    return r.error ? coreProblem(r) : ok(r.data);
  }
  const r = await coreCall(req, cfg, op === "approve" ? "/integration-request-approve" : "/integration-request-redispatch", { requestId: id });
  if (r.error) return coreProblem(r);
  return dispatchApproved(req, cfg, r.data);
}

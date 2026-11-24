// 連携コンソールの手動送信（二者承認）：依頼の一覧（GET）と作成（POST ?kind=zengin|billing|fx）。
// 作成した時点では送信しない。別の役席が /api/integration/requests/{id}/approve で承認したときに Kong へ送る。

import {
  MAX_UPLOAD, SUBMIT_TARGETS, acceptedContentType, checkCsrf, configFromEnv, coreCall, coreProblem, ok, problem, requireSupervisor,
  type SubmitKind,
} from "@/lib/integration-server";

export const dynamic = "force-dynamic";

export async function GET(req: Request): Promise<Response> {
  const cfg = configFromEnv();
  if (!cfg) return problem(503, "外部連携が設定されていません");
  const user = await requireSupervisor(req, cfg);
  if (typeof user !== "string") return user;
  const r = await coreCall(req, cfg, "/integration-requests");
  return r.error ? coreProblem(r) : ok(r.data);
}

export async function POST(req: Request): Promise<Response> {
  const kind = new URL(req.url).searchParams.get("kind") ?? "";
  if (!(kind in SUBMIT_TARGETS)) return problem(404, "見つかりません");
  const cfg = configFromEnv();
  if (!cfg) return problem(503, "外部連携が設定されていません");
  const csrf = checkCsrf(req, cfg);
  if (csrf) return csrf;
  const user = await requireSupervisor(req, cfg);
  if (typeof user !== "string") return user;
  const contentType = acceptedContentType(kind as SubmitKind, req.headers.get("content-type"));
  if (!contentType) return problem(415, "この形式のデータは送信できません");
  const body = Buffer.from(await req.arrayBuffer());
  if (body.byteLength === 0) return problem(422, "データが空です");
  if (body.byteLength > MAX_UPLOAD) return problem(413, "データが大きすぎます（5 MB まで）");
  let fileName: string | null = null;
  try {
    fileName = req.headers.get("x-file-name") ? decodeURIComponent(req.headers.get("x-file-name")!) : null;
  } catch {
    fileName = null;
  }
  const comment = req.headers.get("x-comment") ? decodeURIComponent(req.headers.get("x-comment")!) : null;
  const r = await coreCall(req, cfg, "/integration-request-create", {
    kind: SUBMIT_TARGETS[kind as SubmitKind].kind, contentType, payloadBase64: body.toString("base64"),
    fileName: fileName?.slice(0, 200) ?? null, comment: comment?.slice(0, 200) ?? null,
  });
  return r.error ? coreProblem(r) : ok(r.data, 201);
}

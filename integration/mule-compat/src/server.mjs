// MuleSoft 互換コンテナ：Mule アプリ（integration/mulesoft/bank-integration）と同じ API 契約
// （src/main/resources/api/bank-integration.yaml）を Node.js で提供する。Mule ランタイムが無いローカル環境用。
//
// - 8443（mTLS）：業務 API。Kong のクライアント証明書（社内 CA が発行し、CN が MTLS_ALLOWED_CLIENTS のもの）だけを受け付ける
// - 8081（平文）：死活確認（/live）と指標（/metrics）だけ
// - 冪等性：Idempotency-Key（なければ本文の SHA-256）で勘定系に実行を登録し、完了済みの再送には保存した応答を返す
// - 行員 ID：連携コンソール（Kong の利用者 bank-ops）からの送信は、署名付きトークン（作成者・承認者・本文のハッシュ）を必須にする
// - 実行履歴は勘定系（Oracle）に保存する（再起動しても消えない）

import { createServer } from "node:http";
import { createServer as createTlsServer } from "node:https";
import { createHash, randomUUID } from "node:crypto";
import { pathToFileURL } from "node:url";
import { parseZengin, toIncoming, summarise } from "./zengin.mjs";
import { parseBilling } from "./billing.mjs";
import { parseFx } from "./fx.mjs";
import { createCoreClient, CoreRejected, CoreUnavailable } from "./core.mjs";
import { verifyOperatorToken } from "./operator.mjs";
import { createMetrics, createTracer, parseTraceparent } from "./telemetry.mjs";

const MAX_BODY = 5 * 1024 * 1024;

/** 入力エラー（422）。errors に行番号付きの内容を持つ */
class InvalidInput extends Error {
  constructor(message, errors = []) { super(message); this.errors = errors; }
}
/** 拒否（403） */
class Forbidden extends Error {}

/** 要求本文を読む（上限 5 MB） */
async function readBody(req) {
  const chunks = [];
  let size = 0;
  for await (const c of req) {
    size += c.length;
    if (size > MAX_BODY) throw new InvalidInput("要求が大きすぎます（5 MB まで）。");
    chunks.push(c);
  }
  return Buffer.concat(chunks);
}

/** Content-Type の charset で文字列にする（UTF-8 / Shift_JIS） */
export function decodeText(buf, contentType = "") {
  const m = /charset\s*=\s*"?([\w-]+)/i.exec(contentType);
  const cs = (m?.[1] ?? "utf-8").toLowerCase();
  const label = ["shift_jis", "sjis", "shift-jis", "windows-31j", "cp932", "ms932"].includes(cs) ? "shift_jis" : "utf-8";
  return new TextDecoder(label, { fatal: true }).decode(buf);
}

const sha256 = (buf) => createHash("sha256").update(buf).digest("hex");

export function createApp({
  core, ourBankCode = "9999", chunkSize = 100, log = console.log, operatorSecret = "",
  requireClientCert = false, allowedClients = ["kong-gateway"], metrics = createMetrics(), tracer = createTracer({}),
}) {
  function send(res, status, body, requestId, contentType, extra = {}) {
    res.writeHead(status, { "Content-Type": contentType, "X-Request-ID": requestId, ...extra });
    res.end(typeof body === "string" ? body : JSON.stringify(body));
  }
  function problemBody(status, title, detail, requestId, errors = []) {
    const kind = { 422: "invalid-input", 502: "core-unavailable", 404: "not-found", 403: "forbidden", 409: "conflict", 400: "bad-request" }[status] ?? "internal-error";
    return { type: `https://bank.example/problems/${kind}`, title, status, detail, errors, requestId };
  }

  // ---- 業務処理（本文 → 結果）。例外は呼び出し元で problem に変換する ----
  async function zengin(buf, ct, ctx) {
    const z = parseZengin(decodeText(buf, ct));
    if (z.errors.length > 0) throw new InvalidInput("全銀データの形式・件数・合計金額に誤りがあるため、取り込みませんでした。", z.errors);
    const mapped = toIncoming(z, ourBankCode);
    const coreResults = [];
    for (let i = 0; i < mapped.items.length; i += chunkSize) {
      const chunk = mapped.items.slice(i, i + chunkSize);
      const data = await core.post("/remit-incoming", { items: chunk.map((c) => c.request) }, ctx.requestId, ctx.traceparent);
      chunk.forEach((c, j) => coreResults.push({
        line: c.line, accountNo: c.request.accountNo, amount: Number(c.request.amount), referenceNo: c.request.referenceNo,
        status: data[j]?.status, note: data[j]?.note ?? null,
      }));
    }
    const out = summarise(z, coreResults, mapped.rejected, ctx.runId);
    const s = out.summary;
    return {
      body: out, outcome: s.held + s.rejected > 0 ? "PARTIAL" : "SUCCESS",
      summary: `${out.senderBankCode} ${out.records} 件：入金 ${s.credited}・保留 ${s.held}・重複 ${s.duplicate}・対象外 ${s.rejected}`,
    };
  }

  async function billing(buf, ct, ctx) {
    const b = parseBilling(decodeText(buf, ct));
    if (b.errors.length > 0) throw new InvalidInput("請求データに誤りがあるため、取り込みませんでした。", b.errors);
    const data = await core.post("/billing-import", { items: b.items }, ctx.requestId, ctx.traceparent);
    const results = data.map((r) => ({ billerCode: r.billerCode, payerNumber: r.payerNumber, result: r.result }));
    const taken = results.filter((r) => r.result === "取込").length;
    return {
      body: { runId: ctx.runId, rows: results.length, results },
      outcome: taken === results.length ? "SUCCESS" : "PARTIAL", summary: `${results.length} 行：取込 ${taken} 件`,
    };
  }

  async function fx(buf, ct, ctx) {
    let body;
    try {
      body = JSON.parse(decodeText(buf, ct));
    } catch (e) {
      if (e instanceof TypeError) throw e;
      throw new InvalidInput("JSON として読めません。");
    }
    const f = parseFx(body);
    if (f.errors.length > 0) throw new InvalidInput("為替相場のデータに誤りがあります。", f.errors);
    for (const r of f.supported) {
      await core.post("/fx-rate-update", { currency: r.currency, ttm: String(r.mid), spread: String(r.spread) }, ctx.requestId, ctx.traceparent);
    }
    const updated = f.supported.map((r) => r.currency);
    return {
      body: { runId: ctx.runId, provider: f.provider, updated, skipped: f.skipped }, outcome: "SUCCESS",
      summary: `${f.provider}：更新 ${updated.join("・")}${f.skipped.length ? `（対象外 ${f.skipped.join("・")}）` : ""}`,
    };
  }

  const routes = {
    "POST /api/v1/zengin/incoming": ["ZENGIN_INCOMING", zengin],
    "POST /api/v1/billing/import": ["BILLING_IMPORT", billing],
    "POST /api/v1/fx/rates": ["FX_RATES", fx],
  };

  // 業務処理を実行し、送信側に返す [status, body, contentType, outcome, summary] を作る
  async function process(fn, buf, ct, ctx) {
    try {
      const r = await fn(buf, ct, ctx);
      return [200, r.body, "application/json; charset=utf-8", r.outcome, r.summary];
    } catch (e) {
      const p = (status, title, detail, errors) => [status, problemBody(status, title, detail, ctx.requestId, errors), "application/problem+json"];
      if (e instanceof InvalidInput) return [...p(422, "入力データに誤りがあります", e.message, e.errors), "FAILURE", `入力エラー ${e.errors.length || 1} 件`];
      if (e instanceof CoreRejected) {
        return [...p(422, "入力データに誤りがあります", "勘定系が要求を受け付けませんでした。", [{ line: 0, message: e.message }]), "FAILURE", "勘定系が受け付けませんでした"];
      }
      if (e instanceof CoreUnavailable) return [...p(502, "勘定系システムに接続できません", e.message), "FAILURE", "勘定系に接続できません"];
      if (e instanceof TypeError && /decode|encoding|invalid/i.test(String(e.message))) {
        return [...p(422, "入力データに誤りがあります", "文字コード（charset）が正しくありません。"), "FAILURE", "文字コードが不正"];
      }
      log(JSON.stringify({ event: "error", requestId: ctx.requestId, error: String(e?.stack ?? e) }));
      return [...p(500, "連携処理でエラーが発生しました", "時間をおいて再度お試しください。"), "FAILURE", "想定外のエラー"];
    }
  }

  async function submit(req, res, kind, fn, ctx) {
    const consumer = String(req.headers["x-consumer-username"] ?? (requireClientCert ? "" : "internal"));
    if (!/^[A-Za-z0-9_.-]{1,40}$/.test(consumer)) throw new Forbidden("利用者を確認できません。");
    const buf = await readBody(req);
    const payloadSha256 = sha256(buf);
    const ct = String(req.headers["content-type"] ?? "");

    // 連携コンソールからの送信：作成者・承認者・依頼 ID は署名付きトークンからだけ読む（平文のヘッダーは使わない）
    let operator = null;
    let approver = null;
    let idempotencyKey = req.headers["idempotency-key"] ? String(req.headers["idempotency-key"]) : `sha256:${payloadSha256}`;
    if (consumer === "bank-ops") {
      let claims;
      try {
        claims = verifyOperatorToken(req.headers["x-bank-operator-token"], operatorSecret, payloadSha256);
      } catch (e) {
        log(JSON.stringify({ event: "operator-token-rejected", requestId: ctx.requestId, reason: String(e?.message ?? e) }));
        throw new Forbidden("行員の署名を確認できません（二者承認を経た送信だけを受け付けます）。");
      }
      operator = claims.maker;
      approver = claims.approver;
      idempotencyKey = `approval:${claims.approvalId}`;
    }
    if (!/^[A-Za-z0-9_.:-]{8,80}$/.test(idempotencyKey)) {
      return send(res, 400, problemBody(400, "Idempotency-Key が正しくありません", "英数字と _ . : - の 8〜80 文字にしてください。", ctx.requestId), ctx.requestId, "application/problem+json");
    }

    // 実行を登録する（冪等キーで重複を判定）
    let start;
    try {
      start = await core.post("/integration-run-start", {
        consumer, kind, idempotencyKey, payloadSha256, correlationId: /^[A-Za-z0-9-]{1,64}$/.test(ctx.requestId) ? ctx.requestId : null,
        operator, approver,
      }, ctx.requestId, ctx.traceparent);
    } catch (e) {
      if (e instanceof CoreRejected && e.code === "IDEMPOTENCY_KEY_REUSED") {
        return send(res, 422, problemBody(422, "冪等キーが別の内容で使われています", "同じ Idempotency-Key で内容の違うデータは送れません。", ctx.requestId), ctx.requestId, "application/problem+json");
      }
      const [status, body, type] = [502, problemBody(502, "勘定系システムに接続できません", String(e?.message ?? e), ctx.requestId), "application/problem+json"];
      return send(res, status, body, ctx.requestId, type);
    }
    if (start.state === "REPLAY") {
      metrics.inc("mule_idempotent_replays_total", { kind });
      const status = Number(start.httpStatus) || 200;
      return send(res, status, start.response ?? "{}", ctx.requestId,
        status >= 400 ? "application/problem+json" : "application/json; charset=utf-8", { "Idempotent-Replayed": "true" });
    }
    if (start.state === "IN_PROGRESS") {
      return send(res, 409, problemBody(409, "同じデータを処理中です", "しばらくしてから結果を確認してください（同じ Idempotency-Key で再送できます）。", ctx.requestId),
        ctx.requestId, "application/problem+json", { "Retry-After": "30" });
    }

    ctx.runId = start.runId;
    const [status, body, type, outcome, summary] = await process(fn, buf, ct, ctx);
    // 5xx は再処理できるように FAILED、それ以外（成功・入力エラー）は応答を保存して完了にする
    try {
      await core.post("/integration-run-finish", {
        runId: start.runId, status: status >= 500 ? "FAILED" : "COMPLETED", outcome, summary,
        httpStatus: String(status), responseBody: status >= 500 ? null : JSON.stringify(body),
      }, ctx.requestId, ctx.traceparent);
    } catch (e) {
      // 記録に失敗しても業務処理は確定している。実行は期限切れ後に再処理できる状態になる（業務側の一意制約で二重処理はしない）
      log(JSON.stringify({ event: "run-finish-failed", requestId: ctx.requestId, runId: start.runId, error: String(e?.message ?? e) }));
    }
    metrics.inc("mule_integration_runs_total", { kind, outcome });
    log(JSON.stringify({ event: "integration-run", runId: start.runId, kind, consumer, operator, approver, outcome, status, attempts: start.attempts, requestId: ctx.requestId, traceId: ctx.traceId }));
    return send(res, status, body, ctx.requestId, type);
  }

  async function runs(res, ctx) {
    const data = await core.get("/integration-runs?limit=50", ctx.requestId, ctx.traceparent);
    const list = data.map((r) => ({
      runId: r.runId, kind: r.kind, startedAt: r.startedAt, durationMs: r.durationMs ?? 0, status: r.status,
      outcome: r.outcome ?? (r.status === "PROCESSING" ? "PROCESSING" : "FAILURE"), consumer: r.consumer,
      operator: r.operator ?? null, approver: r.approver ?? null, requestId: r.requestId ?? "", summary: r.summary ?? "",
      attempts: r.attempts ?? 1,
    }));
    return send(res, 200, list, ctx.requestId, "application/json; charset=utf-8");
  }

  function checkClient(req) {
    if (!requireClientCert) return;
    const sock = req.socket;
    const cert = typeof sock.getPeerCertificate === "function" ? sock.getPeerCertificate() : null;
    if (!sock.authorized || !cert?.subject || !allowedClients.includes(cert.subject.CN)) {
      throw new Forbidden("クライアント証明書を確認できません。");
    }
  }

  /** 業務 API（mTLS のポート） */
  async function handle(req, res) {
    const path = new URL(req.url ?? "/", "http://localhost").pathname;
    const requestId = String(req.headers["x-request-id"] ?? randomUUID());
    const trace = parseTraceparent(req.headers.traceparent);
    const span = tracer.start(`${req.method} ${path}`, { traceId: trace.traceId, parentSpanId: trace.parentSpanId, kind: 2, attributes: { "http.method": req.method, "http.route": path } });
    const ctx = { requestId, traceId: trace.traceId, traceparent: span.traceparent, runId: null };
    res.on("finish", () => span.end({ "http.status_code": res.statusCode, "bank.run_id": ctx.runId }, res.statusCode >= 500 ? `HTTP ${res.statusCode}` : null));
    try {
      checkClient(req);
      if (req.method === "GET" && path === "/api/v1/health") {
        const c = await core.health();
        return send(res, 200, { status: c === "UP" ? "UP" : "DEGRADED", runtime: "mule-compat", core: c }, requestId, "application/json; charset=utf-8");
      }
      if (req.method === "GET" && path === "/api/v1/runs") return await runs(res, ctx);
      const route = routes[`${req.method} ${path}`];
      if (!route) return send(res, 404, problemBody(404, "見つかりません", `${req.method} ${path} はありません。`, requestId), requestId, "application/problem+json");
      return await submit(req, res, route[0], route[1], ctx);
    } catch (e) {
      if (e instanceof Forbidden) return send(res, 403, problemBody(403, "拒否しました", e.message, requestId), requestId, "application/problem+json");
      if (e instanceof InvalidInput) return send(res, 422, problemBody(422, "入力データに誤りがあります", e.message, requestId), requestId, "application/problem+json");
      if (e instanceof CoreUnavailable || e instanceof CoreRejected) {
        return send(res, 502, problemBody(502, "勘定系システムに接続できません", e.message, requestId), requestId, "application/problem+json");
      }
      log(JSON.stringify({ event: "error", requestId, error: String(e?.stack ?? e) }));
      return send(res, 500, problemBody(500, "連携処理でエラーが発生しました", "時間をおいて再度お試しください。", requestId), requestId, "application/problem+json");
    }
  }

  /** 死活確認と指標（平文のポート。業務 API は置かない） */
  handle.admin = function admin(req, res) {
    const path = new URL(req.url ?? "/", "http://localhost").pathname;
    if (req.method === "GET" && path === "/live") {
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify({ status: "UP" }));
    }
    if (req.method === "GET" && path === "/metrics") {
      res.writeHead(200, { "Content-Type": "text/plain; version=0.0.4" });
      return res.end(metrics.render());
    }
    res.writeHead(404, { "Content-Type": "application/json" });
    return res.end(JSON.stringify({ error: "not_found" }));
  };

  return handle;
}

const pemEnv = (name) => {
  const v = String(process.env[name] ?? "").trim();
  if (!v) throw new Error(`${name} is not set`);
  return v.startsWith("-----BEGIN") ? v : Buffer.from(v, "base64").toString("utf8");
};

// 直接起動されたとき（コンテナ）だけサーバーを立てる。テストからは createApp を使う
if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const metrics = createMetrics();
  const tracer = createTracer({ endpoint: process.env.OTEL_EXPORTER_OTLP_ENDPOINT || "", serviceName: "mule", log: console.log });
  const core = createCoreClient({
    baseUrl: process.env.CORE_BASE_URL ?? "http://kong:8000/bank/api",
    user: process.env.MULE_CORE_USER ?? "svc-mule",
    password: process.env.MULE_CORE_PASSWORD ?? "",
    onCall: ({ path, result, seconds }) => {
      const p = path.split("?")[0];
      metrics.inc("mule_core_calls_total", { path: p, result });
      if (result !== "retry") metrics.observe("mule_core_call_seconds", { path: p }, seconds);
    },
  });
  const operatorSecret = process.env.OPERATOR_TOKEN_SECRET ?? "";
  if (operatorSecret.length < 32) console.log(JSON.stringify({ event: "warning", message: "OPERATOR_TOKEN_SECRET is not set: submissions from the console are rejected" }));
  const app = createApp({
    core, ourBankCode: process.env.OUR_BANK_CODE ?? "9999", operatorSecret, requireClientCert: true, metrics, tracer,
    allowedClients: String(process.env.MTLS_ALLOWED_CLIENTS ?? "kong-gateway").split(",").map((s) => s.trim()).filter(Boolean),
  });
  const host = process.env.HOST ?? "::";
  const tlsPort = Number(process.env.TLS_PORT ?? 8443);
  createTlsServer({
    cert: pemEnv("MULE_TLS_CERT"), key: pemEnv("MULE_TLS_KEY"), ca: pemEnv("MTLS_CA_CERT"),
    requestCert: true, rejectUnauthorized: true, minVersion: "TLSv1.2",
  }, app).listen(tlsPort, host, () => console.log(JSON.stringify({ event: "started", runtime: "mule-compat", port: tlsPort, tls: "mutual" })));
  // :: は IPv4 と IPv6 の両方で待ち受ける（Railway の社内ネットワークは IPv6 を含む）
  const port = Number(process.env.PORT ?? 8081);
  createServer(app.admin).listen(port, host, () => console.log(JSON.stringify({ event: "started", runtime: "mule-compat", port, purpose: "live,metrics" })));
}

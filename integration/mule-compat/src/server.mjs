// MuleSoft 互換コンテナ：Mule アプリ（integration/mulesoft/bank-integration）と同じ API 契約
// （src/main/resources/api/bank-integration.yaml）を Node.js で提供する。Mule ランタイムが無いローカル環境用で、
// docker compose のサービス名 mule をそのまま Mule ランタイムに差し替えられる（Kong の設定は変えない）。

import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { pathToFileURL } from "node:url";
import { parseZengin, toIncoming, summarise } from "./zengin.mjs";
import { parseBilling } from "./billing.mjs";
import { parseFx } from "./fx.mjs";
import { createCoreClient, CoreRejected, CoreUnavailable } from "./core.mjs";

const MAX_BODY = 5 * 1024 * 1024;

/** 入力エラー（422）。errors に行番号付きの内容を持つ */
class InvalidInput extends Error {
  constructor(message, errors = []) { super(message); this.errors = errors; }
}

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

export function createApp({ core, ourBankCode = "9999", chunkSize = 100, keepRuns = 50, log = console.log }) {
  const runs = [];

  function problem(res, status, title, detail, requestId, errors = []) {
    const kind = status === 422 ? "invalid-input" : status === 502 ? "core-unavailable" : status === 404 ? "not-found" : "internal-error";
    const body = { type: `https://bank.example/problems/${kind}`, title, status, detail, errors, requestId };
    res.writeHead(status, { "Content-Type": "application/problem+json", "X-Request-ID": requestId });
    res.end(JSON.stringify(body));
  }

  function json(res, status, body, requestId) {
    res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "X-Request-ID": requestId });
    res.end(JSON.stringify(body));
  }

  // 実行の開始・保存（明細の口座番号・氏名は残さない）
  function beginRun(req, kind) {
    const consumer = req.headers["x-consumer-username"] ?? "internal";
    return {
      runId: randomUUID(),
      kind,
      started: Date.now(),
      consumer,
      // 行員名は Kong の利用者が bank-ops（連携コンソール）の場合だけ信用する
      operator: consumer === "bank-ops" ? (req.headers["x-bank-operator"] ?? null) : null,
      requestId: req.headers["x-request-id"] ?? randomUUID(),
    };
  }
  function endRun(run, outcome, summary) {
    const entry = {
      runId: run.runId, kind: run.kind, startedAt: new Date(run.started).toISOString(), durationMs: Date.now() - run.started,
      outcome, consumer: run.consumer, operator: run.operator, requestId: run.requestId, summary,
    };
    runs.unshift(entry);
    runs.length = Math.min(runs.length, keepRuns);
    log(JSON.stringify({ event: "integration-run", ...entry }));
  }

  async function zengin(req, run) {
    const z = parseZengin(decodeText(await readBody(req), req.headers["content-type"]));
    if (z.errors.length > 0) throw new InvalidInput("全銀データの形式・件数・合計金額に誤りがあるため、取り込みませんでした。", z.errors);
    const mapped = toIncoming(z, ourBankCode);
    const coreResults = [];
    for (let i = 0; i < mapped.items.length; i += chunkSize) {
      const chunk = mapped.items.slice(i, i + chunkSize);
      const data = await core.post("/remit-incoming", { items: chunk.map((c) => c.request) }, run.requestId);
      chunk.forEach((c, j) => coreResults.push({
        line: c.line, accountNo: c.request.accountNo, amount: Number(c.request.amount), referenceNo: c.request.referenceNo,
        status: data[j]?.status, note: data[j]?.note ?? null,
      }));
    }
    const out = summarise(z, coreResults, mapped.rejected, run.runId);
    const s = out.summary;
    endRun(run, s.held + s.rejected > 0 ? "PARTIAL" : "SUCCESS",
      `${out.senderBankCode} ${out.records} 件：入金 ${s.credited}・保留 ${s.held}・重複 ${s.duplicate}・対象外 ${s.rejected}`);
    return out;
  }

  async function billing(req, run) {
    const b = parseBilling(decodeText(await readBody(req), req.headers["content-type"]));
    if (b.errors.length > 0) throw new InvalidInput("請求データに誤りがあるため、取り込みませんでした。", b.errors);
    const data = await core.post("/billing-import", { items: b.items }, run.requestId);
    const results = data.map((r) => ({ billerCode: r.billerCode, payerNumber: r.payerNumber, result: r.result }));
    const taken = results.filter((r) => r.result === "取込").length;
    endRun(run, taken === results.length ? "SUCCESS" : "PARTIAL", `${results.length} 行：取込 ${taken} 件`);
    return { runId: run.runId, rows: results.length, results };
  }

  async function fx(req, run) {
    let body;
    try {
      body = JSON.parse(decodeText(await readBody(req), req.headers["content-type"]));
    } catch (e) {
      if (e instanceof InvalidInput) throw e;
      throw new InvalidInput("JSON として読めません。");
    }
    const f = parseFx(body);
    if (f.errors.length > 0) throw new InvalidInput("為替相場のデータに誤りがあります。", f.errors);
    for (const r of f.supported) {
      await core.post("/fx-rate-update", { currency: r.currency, ttm: String(r.mid), spread: String(r.spread) }, run.requestId);
    }
    const updated = f.supported.map((r) => r.currency);
    endRun(run, "SUCCESS", `${f.provider}：更新 ${updated.join("・")}${f.skipped.length ? `（対象外 ${f.skipped.join("・")}）` : ""}`);
    return { runId: run.runId, provider: f.provider, updated, skipped: f.skipped };
  }

  const routes = {
    "POST /api/v1/zengin/incoming": ["ZENGIN_INCOMING", zengin],
    "POST /api/v1/billing/import": ["BILLING_IMPORT", billing],
    "POST /api/v1/fx/rates": ["FX_RATES", fx],
  };

  return async function handle(req, res) {
    const path = new URL(req.url ?? "/", "http://localhost").pathname;
    const requestId = String(req.headers["x-request-id"] ?? randomUUID());
    if (req.method === "GET" && path === "/api/v1/health") {
      const c = await core.health();
      return json(res, 200, { status: c === "UP" ? "UP" : "DEGRADED", runtime: "mule-compat", core: c }, requestId);
    }
    if (req.method === "GET" && path === "/api/v1/runs") return json(res, 200, runs, requestId);
    const route = routes[`${req.method} ${path}`];
    if (!route) return problem(res, 404, "見つかりません", `${req.method} ${path} はありません。`, requestId);
    const [kind, fn] = route;
    const run = beginRun(req, kind);
    try {
      json(res, 200, await fn(req, run), requestId);
    } catch (e) {
      if (e instanceof InvalidInput) {
        endRun(run, "FAILURE", `入力エラー ${e.errors.length || 1} 件`);
        return problem(res, 422, "入力データに誤りがあります", e.message, requestId, e.errors);
      }
      if (e instanceof CoreRejected) {
        endRun(run, "FAILURE", "勘定系が受け付けませんでした");
        return problem(res, 422, "入力データに誤りがあります", "勘定系が要求を受け付けませんでした。", requestId, [{ line: 0, message: e.message }]);
      }
      if (e instanceof CoreUnavailable) {
        endRun(run, "FAILURE", "勘定系に接続できません");
        return problem(res, 502, "勘定系システムに接続できません", e.message, requestId);
      }
      if (e instanceof TypeError && /decode|encoding/i.test(String(e.message))) {
        endRun(run, "FAILURE", "文字コードが不正");
        return problem(res, 422, "入力データに誤りがあります", "文字コード（charset）が正しくありません。", requestId);
      }
      endRun(run, "FAILURE", "想定外のエラー");
      log(JSON.stringify({ event: "error", requestId, error: String(e?.stack ?? e) }));
      return problem(res, 500, "連携処理でエラーが発生しました", "時間をおいて再度お試しください。", requestId);
    }
  };
}

// 直接起動されたとき（コンテナ）だけサーバーを立てる。テストからは createApp を使う
if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const core = createCoreClient({
    baseUrl: process.env.CORE_BASE_URL ?? "http://lb:9080/bank/api",
    user: process.env.MULE_CORE_USER ?? "svc-mule",
    password: process.env.MULE_CORE_PASSWORD ?? "",
  });
  const port = Number(process.env.PORT ?? 8081);
  createServer(createApp({ core, ourBankCode: process.env.OUR_BANK_CODE ?? "9999" }))
    // :: は IPv4 と IPv6 の両方で待ち受ける（Railway の社内ネットワークは IPv6 を含む）
    .listen(port, process.env.HOST ?? "::", () => console.log(JSON.stringify({ event: "started", runtime: "mule-compat", port })));
}

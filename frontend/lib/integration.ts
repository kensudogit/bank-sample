// 連携コンソール（/integration）のクライアント。Next.js のサーバー側 API（/api/integration/*）を呼び、
// Kong・MuleSoft の RFC 9457 エラー（problem+json）を画面共通の ApiError に変換する。

import { ApiError } from "./api";
import { invalidate, useQuery } from "./query";

/** MuleSoft の稼働状況 */
export interface IntegrationStatus {
  status: "UP" | "DEGRADED";
  runtime: string;
  core: "UP" | "DOWN";
}

/** 連携の実行履歴 1 件（勘定系の Oracle に保存。再起動しても消えない） */
export interface IntegrationRun {
  runId: string;
  kind: "ZENGIN_INCOMING" | "BILLING_IMPORT" | "FX_RATES";
  startedAt: string;
  durationMs: number;
  status?: "PROCESSING" | "COMPLETED" | "FAILED";
  outcome: "SUCCESS" | "PARTIAL" | "FAILURE" | "PROCESSING";
  consumer: string;
  operator: string | null;
  approver?: string | null;
  requestId: string;
  summary: string;
  attempts?: number;
}

/** 手動送信の依頼（二者承認） */
export interface IntegrationRequest {
  requestId: string;
  kind: "ZENGIN_INCOMING" | "BILLING_IMPORT" | "FX_RATES";
  contentType: string;
  payloadSha256: string;
  payloadBytes: number;
  fileName: string | null;
  commentText: string | null;
  status: "PENDING" | "APPROVED" | "REJECTED" | "SENT" | "FAILED";
  maker: string;
  createdAt: string;
  approver: string | null;
  decidedAt: string | null;
  rejectReason: string | null;
  attempts: number;
  runId: string | null;
  resultStatus: number | null;
}

/** 承認・再送の結果（送信した場合は連携の応答） */
export interface DispatchResult {
  request: IntegrationRequest | null;
  httpStatus: number;
  result: any;
  kind: SubmitKind;
}

/** 全銀データの取込結果 */
export interface ZenginResult {
  runId: string;
  senderBankCode: string;
  remitterNameKana: string;
  tradeDate: string;
  records: number;
  totalAmount: number;
  summary: { credited: number; held: number; duplicate: number; rejected: number };
  results: { line: number; accountNo: string; amount: number; referenceNo: string; status: string; note: string | null }[];
}

/** 送信できるデータの種類 */
export type SubmitKind = "zengin" | "billing" | "fx";

const BASE = "/api/integration";

/** problem+json を ApiError に変換する（errors は行番号付きで項目エラーとして表示する） */
export async function toError(res: Response): Promise<ApiError> {
  const p = (await res.json().catch(() => null)) as
    | { title?: string; detail?: string; type?: string; errors?: { line?: number; message: string }[]; message?: string }
    | null;
  const message = p?.title ? `${p.title}${p.detail ? `：${p.detail}` : ""}` : p?.message ?? `エラーが発生しました（HTTP ${res.status}）。`;
  const lines = (p?.errors ?? []).map((e) => (e.line ? `${e.line} 行目：${e.message}` : e.message));
  const code = p?.type?.split("/").pop()?.toUpperCase().replace(/-/g, "_") ?? "SYSTEM_ERROR";
  return new ApiError(res.status, code, message, lines.length ? { errors: lines } : {});
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${BASE}${path}`, { cache: "no-store", ...init });
  } catch {
    throw new ApiError(0, "NETWORK_ERROR", "サーバーに接続できません。");
  }
  if (!res.ok) throw await toError(res);
  return (await res.json()) as T;
}

// セッションに結び付いた CSRF トークン（更新要求に X-CSRF-Token で付ける）。拒否されたら 1 回だけ取り直す
let csrf: string | null = null;
async function csrfToken(refresh = false): Promise<string> {
  if (!csrf || refresh) csrf = (await call<{ token: string }>("/csrf")).token;
  return csrf;
}
/** テスト用：CSRF トークンのキャッシュを消す */
export function resetCsrf(): void {
  csrf = null;
}

async function write<T>(path: string, init: RequestInit): Promise<T> {
  const send = async (refresh: boolean) =>
    call<T>(path, {
      ...init, method: "POST",
      headers: { ...(init.headers as Record<string, string>), "X-Requested-With": "XMLHttpRequest", "X-CSRF-Token": await csrfToken(refresh) },
    });
  try {
    return await send(false);
  } catch (e) {
    if (e instanceof ApiError && e.status === 403 && /再読み込み/.test(e.message)) return send(true);
    throw e;
  }
}

export const integrationApi = {
  status: () => call<IntegrationStatus>("/status"),
  runs: () => call<IntegrationRun[]>("/runs"),
  requests: () => call<IntegrationRequest[]>("/requests"),
  /** 承認依頼を作成する（この時点では送信しない） */
  createRequest: async (kind: SubmitKind, body: BodyInit, contentType: string, fileName?: string | null, comment?: string | null) => {
    try {
      const headers: Record<string, string> = { "Content-Type": contentType };
      if (fileName) headers["X-File-Name"] = encodeURIComponent(fileName);
      if (comment) headers["X-Comment"] = encodeURIComponent(comment);
      return await write<IntegrationRequest>(`/requests?kind=${kind}`, { body, headers });
    } finally {
      invalidate("integration-requests");
    }
  },
  /** 承認して送信する（作成者以外の役席）。送信後は残高・明細なども変わるため、画面のキャッシュをすべて古い扱いにする */
  approve: async (id: string) => {
    try {
      return await write<DispatchResult>(`/requests/${encodeURIComponent(id)}/approve`, {});
    } finally {
      invalidate("*");
    }
  },
  /** 失敗した送信を再送する（同じ依頼 ID を冪等キーにするので二重には処理されない） */
  retry: async (id: string) => {
    try {
      return await write<DispatchResult>(`/requests/${encodeURIComponent(id)}/retry`, {});
    } finally {
      invalidate("*");
    }
  },
  reject: async (id: string, reason: string) => {
    try {
      return await write<IntegrationRequest>(`/requests/${encodeURIComponent(id)}/reject`, {
        body: JSON.stringify({ reason }), headers: { "Content-Type": "application/json" },
      });
    } finally {
      invalidate("integration-requests");
    }
  },
};

export const useIntegrationStatus = () => useQuery("integration-status", integrationApi.status);
export const useIntegrationRuns = () => useQuery("integration-runs", integrationApi.runs);
export const useIntegrationRequests = () => useQuery("integration-requests", integrationApi.requests);
// ---- サンプルデータ（画面の「サンプルを入れる」用。架空のデータ） ----

const padRight = (s: string, n: number) => (s + " ".repeat(n)).slice(0, n);
const padNumber = (v: number, n: number) => String(v).padStart(n, "0");

/** 全銀フォーマット（総合振込・被仕向）のサンプル。取組日は今日、宛先は当行（9999）の口座 1000003（名義一致 1 件・不一致 1 件） */
export function sampleZengin(today = new Date()): string {
  const mmdd = padNumber(today.getMonth() + 1, 2) + padNumber(today.getDate(), 2);
  // 顧客コード1（照会番号の一部）は作成時刻から作る：初回は入金・保留になり、同じデータの再送は重複として返る
  const hms = padNumber(today.getHours(), 2) + padNumber(today.getMinutes(), 2) + padNumber(today.getSeconds(), 2);
  const data: [string, string, number, string][] = [
    ["1000003", "ｻﾄｳ ﾊﾅｺ", 12000, `${hms}0001`],
    ["1000003", "ﾀﾅｶ ｲﾁﾛｳ", 3000, `${hms}0002`],
  ];
  const total = data.reduce((s, d) => s + d[2], 0);
  const lines = [
    "1" + "21" + "0" + padNumber(1234567890, 10) + padRight("ｶ)ﾃｽﾄｼﾖｳｼﾞ", 40) + mmdd + "0005" + padRight("ﾐﾂﾋﾞｼﾕ-ｴﾌｼﾞｴｲ", 15)
      + "001" + padRight("ﾎﾝﾃﾝ", 15) + "1" + padNumber(1234567, 7) + padRight("", 17),
    ...data.map(([acc, name, amount, code]) =>
      "2" + "9999" + padRight("ｴｲｺﾞｳ", 15) + "001" + padRight("ﾎﾝﾃﾝ", 15) + padRight("", 4) + "1" + acc + padRight(name, 30)
        + padNumber(amount, 10) + "0" + code + padRight("", 10) + "7" + " " + padRight("", 7)),
    "8" + padNumber(data.length, 6) + padNumber(total, 12) + padRight("", 101),
    "9" + padRight("", 119),
  ];
  return lines.join("\r\n") + "\r\n";
}

export const SAMPLE_BILLING = "収納企業コード,お客さま番号,金額,引落日\r\nELEC01,E-0001,8000,2026-12-27\r\n";

export const SAMPLE_FX = JSON.stringify({
  provider: "DEMO-FX",
  asOf: "2026-11-24T09:55:00+09:00",
  rates: [
    { currency: "USD", mid: "152.30", spread: "1.00" },
    { currency: "EUR", mid: "163.10", spread: "1.50" },
    { currency: "GBP", mid: "190.00", spread: "2.00" },
  ],
}, null, 2);

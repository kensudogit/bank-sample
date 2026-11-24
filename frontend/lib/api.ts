// 勘定系 API（口座・入出金・振込・顧客・利息・休眠・監査・ジョブ）のクライアント。
// fetch の共通処理（エラー変換・401 検知・CSRF 対策ヘッダー）、キャッシュキー、キャッシュ付き読み取りフック、
// 更新後のキャッシュ無効化（mutate）と冪等キー生成を提供する。

import { clearCache, invalidate, prefetch, useQuery } from "./query";
import type {
  Account,
  AccountDetail,
  AccountPage,
  AuditEntry,
  AuditVerification,
  JobsInfo,
  LoginUser,
  Page,
  ApiErrorBody,
  CloseResult,
  Customer,
  DormancyReport,
  IdDocType,
  InterestResult,
  LedgerTraceItem,
  LedgerVerification,
  OperationResult,
  SettlementSummary,
  TransactionPage,
  TxType,
} from "./types";

/** API error carrying the server's error code and field errors. */
// 画面では code で分岐し、fieldErrors を各入力欄の下に表示する
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fieldErrors: Record<string, string[]> = {},
  ) {
    super(message);
    this.name = "ApiError";
  }
}

// next.config.ts の rewrites で Liberty の /bank/api/* に転送される、同一オリジンのパス
const BASE = "/api/bank";

/** Fired when the server says the session is gone (expired / logged out elsewhere). AuthProvider listens. */
// セッション切れを検知したら window イベントで通知し、AuthProvider がログイン画面へ誘導する
export const UNAUTHENTICATED_EVENT = "bank:unauthenticated";

/**
 * API 呼び出しの共通関数。body を渡すと POST（JSON）、省略すると GET になる。
 * 応答は { data } / { error } 形式で、失敗時は ApiError を投げる。
 * ログイン・パスワード変更以外で 401 が返ったらセッション切れイベントを発火する。
 */
export async function request<T>(path: string, body?: unknown): Promise<T> {
  const init: RequestInit = { cache: "no-store", headers: { Accept: "application/json" } };
  if (body !== undefined) {
    init.method = "POST";
    init.body = JSON.stringify(body);
    // Required by the API guard (CSRF mitigation for state-changing requests)
    init.headers = { ...init.headers, "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest" };
  }

  let res: Response;
  try {
    res = await fetch(`${BASE}${path}`, init);
  } catch {
    throw new ApiError(0, "NETWORK_ERROR", "サーバーに接続できません。");
  }

  let json: { data?: T; error?: ApiErrorBody } | null = null;
  try {
    json = await res.json();
  } catch {
    // non-JSON (e.g. proxy error page)
  }
  if (res.status === 401 && path !== "/login" && path !== "/password-change" && typeof window !== "undefined") {
    window.dispatchEvent(new Event(UNAUTHENTICATED_EVENT));
  }
  if (!res.ok || !json || json.error) {
    const err = json?.error;
    throw new ApiError(
      res.status,
      err?.code ?? "SYSTEM_ERROR",
      err?.message ?? `サーバーエラーが発生しました（HTTP ${res.status}）。`,
      err?.fieldErrors ?? {},
    );
  }
  return json.data as T;
}

/** Builds a query string, skipping empty values. */
// 未入力の検索条件を送らないことで、サーバー側で「条件なし」として扱わせる
export function qs(params: Record<string, string | number | undefined | null>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") q.set(k, String(v));
  }
  const s = q.toString();
  return s ? `?${s}` : "";
}

/** 入金・出金リクエスト。idempotencyKey で通信再送時の二重入出金を防ぐ。 */
export interface CashRequest {
  accountNo: string;
  amount: string;
  memo?: string;
  idempotencyKey: string;
}

/** 振込・振替リクエスト */
export interface TransferRequest {
  fromAccountNo: string;
  toAccountNo: string;
  amount: string;
  memo?: string;
  idempotencyKey: string;
  /** true: 振替（同じ顧客の口座間に限る） */
  ownAccountsOnly?: boolean;
}

/** 顧客登録（新規口座開設の前段）リクエスト */
export interface RegisterCustomerRequest {
  customerType?: "INDIVIDUAL" | "CORPORATE";
  name: string;
  nameKana: string;
  birthDate: string;
  postalCode: string;
  address: string;
  phone: string;
  idDocType: IdDocType;
  idDocNumber: string;
  idempotencyKey: string;
}

/** 入出金明細の検索条件（期間・取引種別・ページ） */
export interface TransactionQuery {
  accountNo: string;
  from?: string;
  to?: string;
  types?: TxType[];
  page?: number;
  size?: number;
}

/** 監査ログの検索条件 */
export interface AuditQuery {
  from?: string;
  to?: string;
  userId?: string;
  auditAction?: string;
  outcome?: string;
  page?: number;
  size?: number;
}

/** 認証 API。ログイン・ログアウト時はキャッシュを全消去し、前の利用者のデータが見えないようにする。 */
export const authApi = {
  me: () => request<LoginUser | null>("/me"),
  login: async (userId: string, password: string) => {
    const user = await request<LoginUser>("/login", { userId, password });
    clearCache();   // never show the previous user's data
    return user;
  },
  logout: async () => {
    try {
      await request<null>("/logout", {});
    } finally {
      clearCache();
    }
  },
  changePassword: (currentPassword: string, newPassword: string) =>
    request<LoginUser>("/password-change", { currentPassword, newPassword }),
};

/** 勘定系の業務 API。更新系は mutate() で包み、成功・失敗にかかわらず画面のキャッシュを無効化する。 */
export const bankApi = {
  searchAccounts: (q: { q?: string; page?: number; size?: number }) =>
    request<AccountPage>(`/accounts${qs({ q: q.q, page: q.page, size: q.size })}`),
  getAccount: (accountNo: string) => request<AccountDetail>(`/account${qs({ accountNo })}`),
  searchTransactions: (q: TransactionQuery) =>
    request<TransactionPage>(
      `/transactions${qs({ accountNo: q.accountNo, from: q.from, to: q.to, types: q.types?.join(","), page: q.page, size: q.size })}`,
    ),
  previewInterest: (accountNo: string, asOf?: string) =>
    request<InterestResult>(`/interest-preview${qs({ accountNo, asOf })}`),
  listCustomers: () => request<Customer[]>("/customers"),
  getCustomer: (customerId: string) => request<Customer>(`/customer${qs({ customerId })}`),
  dormancyReport: (asOf?: string) => request<DormancyReport>(`/dormancy${qs({ asOf })}`),

  deposit: (req: CashRequest) => mutate(request<OperationResult>("/deposit", req)),
  withdraw: (req: CashRequest) => mutate(request<OperationResult>("/withdraw", req)),
  transfer: (req: TransferRequest) => mutate(request<OperationResult>("/transfer", req)),
  registerCustomer: (req: RegisterCustomerRequest) => mutate(request<Customer>("/customer-register", req)),
  decideKyc: (req: { customerId: string; decision: "VERIFIED" | "REJECTED"; note?: string }) =>
    mutate(request<Customer>("/customer-kyc", req)),
  openAccount: (req: { customerId: string; accountType: string; currency?: string; initialDeposit: string; idempotencyKey: string }) =>
    mutate(request<Account>("/account-open", req)),
  closeAccount: (req: { accountNo: string; payoutAccountNo?: string; idempotencyKey: string }) =>
    mutate(request<CloseResult>("/account-close", req)),
  reactivateAccount: (req: { accountNo: string; idempotencyKey: string }) =>
    mutate(request<Account>("/account-reactivate", req)),
  settleInterest: (req: { asOf?: string; accountNo?: string }) =>
    mutate(request<SettlementSummary>("/interest-settle", req)),
  runDormancy: (req: { asOf?: string }) => mutate(request<DormancyReport>("/dormancy-run", req)),
  issueCustomerLogin: (customerId: string) =>
    mutate(request<{ userId: string; initialPassword: string }>("/customer-login-issue", { customerId })),
  auditLog: (q: AuditQuery) =>
    request<Page<AuditEntry>>(`/audit-log${qs({ ...q })}`),
  auditVerify: () => request<AuditVerification>("/audit-verify"),
  ledgerVerify: (accountNo?: string) => request<LedgerVerification>(`/ledger-verify${qs({ accountNo })}`),
  ledgerTrace: (accountNo: string) => request<LedgerTraceItem[]>(`/ledger-trace${qs({ accountNo })}`),
  jobs: () => request<JobsInfo>("/jobs"),
  runJobs: () => mutate(request<string[]>("/jobs-run", {})),
};

/** Cache keys for `useQuery` / `prefetch` (see lib/query.ts). */
// キーに検索条件をすべて含めることで、条件ごとに別のキャッシュとして保持する
export const queryKeys = {
  accounts: (q: { q?: string; page?: number; size?: number }) => `accounts:${q.q ?? ""}:${q.page ?? 1}:${q.size ?? 50}`,
  audit: (q: AuditQuery) => `audit:${JSON.stringify(q)}`,
  jobs: "jobs",
  account: (accountNo: string) => `account:${accountNo}`,
  transactions: (q: TransactionQuery) =>
    `tx:${q.accountNo}:${q.from ?? ""}:${q.to ?? ""}:${(q.types ?? []).join(",")}:${q.page ?? 1}:${q.size ?? 50}`,
  interest: (accountNo: string, asOf: string) => `interest:${accountNo}:${asOf}`,
  customers: "customers",
  customer: (customerId: string) => `customer:${customerId}`,
  dormancy: (asOf: string) => `dormancy:${asOf}`,
};

/** Cached readers: show the last data instantly, refresh in the background. */
/** Account list page. Customers automatically get only their own accounts. */
// 以下、各画面から使うキャッシュ付き読み取りフックと、ホバー時の先読み（prefetch）関数
export const useAccounts = (q: { q?: string; page?: number; size?: number } = {}) =>
  useQuery(queryKeys.accounts(q), () => bankApi.searchAccounts(q), { keepPreviousWithin: "accounts" });
export const prefetchAccounts = (q: { q?: string; page?: number; size?: number }) =>
  prefetch(queryKeys.accounts(q), () => bankApi.searchAccounts(q));
export const useAuditLog = (q: AuditQuery) =>
  useQuery(queryKeys.audit(q), () => bankApi.auditLog(q), { keepPreviousWithin: "audit" });
export const prefetchAuditLog = (q: AuditQuery) => prefetch(queryKeys.audit(q), () => bankApi.auditLog(q));
export const useJobs = () => useQuery(queryKeys.jobs, bankApi.jobs);
export const useAccountDetail = (accountNo: string) =>
  useQuery(queryKeys.account(accountNo), () => bankApi.getAccount(accountNo));
export const prefetchAccountDetail = (accountNo: string) =>
  prefetch(queryKeys.account(accountNo), () => bankApi.getAccount(accountNo));
export const useTransactions = (q: TransactionQuery) =>
  useQuery(queryKeys.transactions(q), () => bankApi.searchTransactions(q), { keepPreviousWithin: `tx:${q.accountNo}` });
export const prefetchTransactions = (q: TransactionQuery) =>
  prefetch(queryKeys.transactions(q), () => bankApi.searchTransactions(q));
export const useInterestPreview = (accountNo: string, asOf: string) =>
  useQuery(queryKeys.interest(accountNo, asOf), () => bankApi.previewInterest(accountNo, asOf));
export const useCustomers = () => useQuery(queryKeys.customers, bankApi.listCustomers);
export const useCustomer = (customerId: string) =>
  useQuery(queryKeys.customer(customerId), () => bankApi.getCustomer(customerId));
export const useDormancyReport = (asOf: string) =>
  useQuery(queryKeys.dormancy(asOf), () => bankApi.dormancyReport(asOf));

/**
 * Any change can affect balances, history, interest, customer and dormancy views, and the outcome may be
 * unknown (a timeout or DUPLICATE_REQUEST can mean it went through). So every cached view is marked stale;
 * only the ones on screen are re-fetched right away.
 */
// 更新系 API を包むヘルパー。失敗時もサーバー側で処理済みの可能性があるため、finally で必ず無効化する。
export async function mutate<T>(op: Promise<T>): Promise<T> {
  try {
    return await op;
  } finally {
    invalidate("*");
  }
}

/** One key per logical operation; reuse it on retry, renew it after success. */
// 冪等キー：同じキーで再送されたリクエストはサーバーが二重実行しない。リトライ時は同じキーを使い、成功後に作り直す。
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

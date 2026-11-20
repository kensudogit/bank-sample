import { invalidate, prefetch, useQuery } from "./query";
import type {
  Account,
  AccountDetail,
  ApiErrorBody,
  CloseResult,
  Customer,
  DormancyReport,
  IdDocType,
  InterestResult,
  OperationResult,
  SettlementSummary,
  TransactionPage,
  TxType,
} from "./types";

/** API error carrying the server's error code and field errors. */
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

const BASE = "/api/bank";

async function request<T>(path: string, body?: unknown): Promise<T> {
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
function qs(params: Record<string, string | number | undefined | null>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") q.set(k, String(v));
  }
  const s = q.toString();
  return s ? `?${s}` : "";
}

export interface CashRequest {
  accountNo: string;
  amount: string;
  memo?: string;
  idempotencyKey: string;
}

export interface TransferRequest {
  fromAccountNo: string;
  toAccountNo: string;
  amount: string;
  memo?: string;
  idempotencyKey: string;
  /** true: 振替（同じ顧客の口座間に限る） */
  ownAccountsOnly?: boolean;
}

export interface RegisterCustomerRequest {
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

export interface TransactionQuery {
  accountNo: string;
  from?: string;
  to?: string;
  types?: TxType[];
  page?: number;
  size?: number;
}

export const bankApi = {
  listAccounts: () => request<Account[]>("/accounts"),
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
  openAccount: (req: { customerId: string; accountType: string; initialDeposit: string; idempotencyKey: string }) =>
    mutate(request<Account>("/account-open", req)),
  closeAccount: (req: { accountNo: string; payoutAccountNo?: string; idempotencyKey: string }) =>
    mutate(request<CloseResult>("/account-close", req)),
  reactivateAccount: (req: { accountNo: string; idempotencyKey: string }) =>
    mutate(request<Account>("/account-reactivate", req)),
  settleInterest: (req: { asOf?: string; accountNo?: string }) =>
    mutate(request<SettlementSummary>("/interest-settle", req)),
  runDormancy: (req: { asOf?: string }) => mutate(request<DormancyReport>("/dormancy-run", req)),
};

/** Cache keys for `useQuery` / `prefetch` (see lib/query.ts). */
export const queryKeys = {
  accounts: "accounts",
  account: (accountNo: string) => `account:${accountNo}`,
  transactions: (q: TransactionQuery) =>
    `tx:${q.accountNo}:${q.from ?? ""}:${q.to ?? ""}:${(q.types ?? []).join(",")}:${q.page ?? 1}:${q.size ?? 50}`,
  interest: (accountNo: string, asOf: string) => `interest:${accountNo}:${asOf}`,
  customers: "customers",
  customer: (customerId: string) => `customer:${customerId}`,
  dormancy: (asOf: string) => `dormancy:${asOf}`,
};

/** Cached readers: show the last data instantly, refresh in the background. */
export const useAccounts = () => useQuery(queryKeys.accounts, bankApi.listAccounts);
export const useAccountDetail = (accountNo: string) =>
  useQuery(queryKeys.account(accountNo), () => bankApi.getAccount(accountNo));
export const prefetchAccountDetail = (accountNo: string) =>
  prefetch(queryKeys.account(accountNo), () => bankApi.getAccount(accountNo));
export const useTransactions = (q: TransactionQuery) =>
  useQuery(queryKeys.transactions(q), () => bankApi.searchTransactions(q));
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
async function mutate<T>(op: Promise<T>): Promise<T> {
  try {
    return await op;
  } finally {
    invalidate("*");
  }
}

/** One key per logical operation; reuse it on retry, renew it after success. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

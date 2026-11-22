import { mutate, qs, request } from "./api";
import { useQuery } from "./query";

/** Rows from the generic banking endpoints (column names in camelCase). */
export type Row = Record<string, any>;

const get = <T = Row>(path: string, params: Record<string, string | number | undefined | null> = {}) =>
  request<T>(`${path}${qs(params)}`);
const post = <T = Row>(path: string, body: unknown) => mutate(request<T>(path, body));

// ---------------- deposits ----------------
export const depositApi = {
  timeRates: () => get<Row[]>("/time-rates"),
  openTime: (b: { sourceAccountNo: string; amount: string; termMonths: string; instruction: string; idempotencyKey: string }) =>
    post("/time-open", b),
  timeDeposit: (accountNo: string) => get("/time-deposit", { accountNo }),
  cancelTime: (accountNo: string, idempotencyKey: string) => post("/time-cancel", { accountNo, idempotencyKey }),
  notices: (accountNo: string) => get<Row[]>("/notices", { accountNo }),
  notify: (accountNo: string, amount: string) => post("/notice-create", { accountNo, amount }),
  cancelNotice: (accountNo: string, noticeId: string) => post<Row[]>("/notice-cancel", { accountNo, noticeId }),
  fxRates: () => get<Row[]>("/fx-rates"),
  updateRate: (currency: string, ttm: string, spread: string) => post<Row[]>("/fx-rate-update", { currency, ttm, spread }),
  exchange: (b: { fromAccountNo: string; toAccountNo: string; amount: string; idempotencyKey: string }) =>
    post("/fx-exchange", b),
};

// ---------------- loans ----------------
export const loanApi = {
  applications: (status?: string) => get<Row[]>("/loan-applications", { status }),
  application: (applicationId: string) => get("/loan-application", { applicationId }),
  apply: (b: Record<string, string>) => post("/loan-apply", b),
  screen: (applicationId: string) => post("/loan-screen", { applicationId }),
  decide: (b: { applicationId: string; decision: "APPROVE" | "REJECT"; note?: string; rate?: string }) =>
    post("/loan-decide", b),
  collateral: (b: Record<string, string>) => post("/loan-collateral", b),
  participant: (b: Record<string, string>) => post("/loan-participant", b),
  contract: (b: { applicationId: string; repaymentAccountNo: string; paymentDay: string }) => post("/loan-contract", b),
  disburse: (loanId: string, idempotencyKey: string) => post("/loan-disburse", { loanId, idempotencyKey }),
  loans: (status?: string) => get<Row[]>("/loans", { status }),
  loan: (loanId: string) => get("/loan", { loanId }),
  draw: (loanId: string, amount: string, idempotencyKey: string) => post("/loan-draw", { loanId, amount, idempotencyKey }),
  repay: (loanId: string, amount: string, idempotencyKey: string) => post("/loan-repay", { loanId, amount, idempotencyKey }),
  prepay: (loanId: string, amount: string, idempotencyKey: string) => post("/loan-prepay", { loanId, amount, idempotencyKey }),
  collection: (b: { loanId: string; actionType: string; note: string; promisedDate?: string }) => post("/loan-collection", b),
  writeOff: (loanId: string, note: string) => post("/loan-writeoff", { loanId, note }),
  setClass: (loanId: string, borrowerClass: string) => post("/loan-class", { loanId, borrowerClass }),
  processDue: () => post("/loan-process-due", {}),
  // 融資管理：返済シミュレーション・引当・担保の再評価・条件変更
  simulate: (q: { amount: string; rate: string; termMonths: string; repaymentMethod: string; paymentDay?: string }) =>
    get("/loan-simulate", q),
  management: (loanId: string) => get("/loan-management", { loanId }),
  provisionRun: () => post("/loan-provision-run", {}),
  provisionSummary: () => get("/loan-provision-summary"),
  revalue: (b: { collateralId: string; appraisalValue: string; note: string }) => post("/collateral-revalue", b),
  reschedule: (b: { loanId: string; termMonths: string; rate?: string; note: string }) => post("/loan-reschedule", b),
};

// ---------------- payments ----------------
export const paymentApi = {
  banks: () => get<Row[]>("/banks"),
  send: (b: Record<string, string>) => post("/remit-send", b),
  remittances: (direction?: string, status?: string) => get<Row[]>("/remittances", { direction, status }),
  cancel: (remitId: string) => post("/remit-cancel", { remitId }),
  returnRemit: (remitId: string, reason: string) => post("/remit-return", { remitId, reason }),
  createZengin: () => post("/zengin-create", {}),
  zenginBatches: () => get<Row[]>("/zengin-batches"),
  zenginBatch: (batchId: string) => get("/zengin-batch", { batchId }),
  settleZengin: (batchId: string) => post("/zengin-settle", { batchId }),
  incoming: (items: Row[]) => post<Row[]>("/remit-incoming", { items }),
  resolve: (remitId: string, decision: "CREDIT" | "RETURN", accountNo?: string) =>
    post("/remit-resolve", { remitId, decision, accountNo }),
  billers: () => get<Row[]>("/billers"),
  mandates: () => get<Row[]>("/mandates"),
  createMandate: (accountNo: string, billerCode: string, payerNumber: string) =>
    post("/mandate-create", { accountNo, billerCode, payerNumber }),
  cancelMandate: (mandateId: string) => post("/mandate-cancel", { mandateId }),
  importBilling: (items: Row[]) => post<Row[]>("/billing-import", { items }),
  debitRequests: () => get<Row[]>("/debit-requests"),
  processDebits: () => post("/debit-process", {}),
  sendForeign: (b: Record<string, string>) => post("/foreign-send", b),
  foreign: (status?: string) => get<Row[]>("/foreign-remittances", { status }),
  foreignOne: (remitId: string) => get("/foreign-remittance", { remitId }),
  decideForeign: (remitId: string, decision: "RELEASE" | "REJECT", note: string) =>
    post("/foreign-decide", { remitId, decision, note }),
};

// ---------------- risk / AML / CRM ----------------
export const riskApi = {
  dashboard: () => get("/risk-dashboard"),
  alerts: (status?: string) => get<Row[]>("/aml-alerts", { status }),
  updateAlert: (alertId: string, status: string, note?: string) => post("/aml-alert-update", { alertId, status, note }),
  scan: () => post("/aml-scan", {}),
  customer360: (customerId: string) => get("/customer-360", { customerId }),
  addNote: (customerId: string, noteType: string, content: string) =>
    post<Row[]>("/customer-note", { customerId, noteType, content }),
};

// ---------------- information systems (DWH / MIS / analytics / reports) ----------------
export type ReportType = "DEPOSIT_BALANCE" | "LOAN_BALANCE" | "TX_SUMMARY" | "KPI_TREND" | "CUSTOMER_ASSETS";
export const infoApi = {
  status: () => get("/dw-status"),
  load: (from: string, to: string) => post<Row[]>("/dw-load", { from, to }),
  dashboard: (from?: string, to?: string) => get("/mis-dashboard", { from, to }),
  customers: (date?: string) => get("/customer-analytics", { date }),
  report: (type: ReportType, q: { date?: string; from?: string; to?: string }) =>
    get("/info-report", { type, date: q.date, from: q.from, to: q.to }),
};

// ---------------- cached readers ----------------
export const useTimeRates = () => useQuery("time-rates", depositApi.timeRates);
export const useFxRates = () => useQuery("fx-rates", depositApi.fxRates);
export const useTimeDeposit = (accountNo: string) => useQuery(`time:${accountNo}`, () => depositApi.timeDeposit(accountNo));
export const useNotices = (accountNo: string) => useQuery(`notices:${accountNo}`, () => depositApi.notices(accountNo));
export const useLoanApplications = (status?: string) =>
  useQuery(`loan-apps:${status ?? ""}`, () => loanApi.applications(status));
export const useLoanApplication = (id: string) => useQuery(`loan-app:${id}`, () => loanApi.application(id));
export const useLoans = (status?: string) => useQuery(`loans:${status ?? ""}`, () => loanApi.loans(status));
export const useLoan = (id: string) => useQuery(`loan:${id}`, () => loanApi.loan(id));
export const useBanks = () => useQuery("banks", paymentApi.banks);
export const useRemittances = (direction?: string, status?: string) =>
  useQuery(`remits:${direction ?? ""}:${status ?? ""}`, () => paymentApi.remittances(direction, status));
export const useZenginBatches = () => useQuery("zengin", paymentApi.zenginBatches);
export const useBillers = () => useQuery("billers", paymentApi.billers);
export const useMandates = () => useQuery("mandates", paymentApi.mandates);
export const useDebitRequests = () => useQuery("debit-requests", paymentApi.debitRequests);
export const useForeignRemittances = (status?: string) =>
  useQuery(`foreign:${status ?? ""}`, () => paymentApi.foreign(status));
export const useRiskDashboard = () => useQuery("risk", riskApi.dashboard);
export const useAmlAlerts = (status?: string) => useQuery(`aml:${status ?? ""}`, () => riskApi.alerts(status));
export const useCustomer360 = (customerId: string) => useQuery(`c360:${customerId}`, () => riskApi.customer360(customerId));
export const useMisDashboard = (from?: string, to?: string) =>
  useQuery(`mis:${from ?? ""}:${to ?? ""}`, () => infoApi.dashboard(from, to), { keepPreviousWithin: "mis" });
export const useCustomerAnalytics = (date?: string) =>
  useQuery(`analytics:${date ?? ""}`, () => infoApi.customers(date), { keepPreviousWithin: "analytics" });
export const useInfoReport = (type: ReportType, q: { date?: string; from?: string; to?: string }) =>
  useQuery(`report:${type}:${q.date ?? ""}:${q.from ?? ""}:${q.to ?? ""}`, () => infoApi.report(type, q),
    { keepPreviousWithin: "report" });
export const useLoanManagement = (loanId: string) => useQuery(`loan-mgmt:${loanId}`, () => loanApi.management(loanId));
export const useProvisionSummary = () => useQuery("provision-summary", loanApi.provisionSummary);

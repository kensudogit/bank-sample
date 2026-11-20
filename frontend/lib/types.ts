export type AccountType = "ORDINARY" | "SAVINGS" | "CHECKING" | "TIME" | "FOREIGN" | "NOTICE";
export type Currency = "JPY" | "USD" | "EUR";
export type AccountStatus = "ACTIVE" | "FROZEN" | "DORMANT" | "CLOSED";
export type TxType =
  | "DEPOSIT"
  | "WITHDRAWAL"
  | "TRANSFER_OUT"
  | "TRANSFER_IN"
  | "INTEREST"
  | "TAX"
  | "CLOSE_PAYOUT"
  | "FX_OUT"
  | "FX_IN"
  | "LOAN_DISB"
  | "LOAN_REPAY"
  | "FEE"
  | "DIRECT_DEBIT"
  | "REMIT_OUT"
  | "REMIT_IN"
  | "OD_INTEREST";
export type KycStatus = "PENDING" | "VERIFIED" | "REJECTED";
export type IdDocType = "DRIVER_LICENSE" | "MY_NUMBER_CARD" | "PASSPORT" | "RESIDENCE_CARD";
export type Role = "CUSTOMER" | "TELLER" | "SUPERVISOR";

export interface LoginUser {
  userId: string;
  displayName: string;
  role: Role;
  /** CUSTOMER only */
  customerId: string | null;
  mustChangePassword: boolean;
  customer: boolean;
  staff: boolean;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  hasNext: boolean;
}

export interface AccountPage extends Page<Account> {
  totalBalance: number;
}

export interface AuditEntry {
  auditId: number;
  occurredAt: string;
  userId: string | null;
  userRole: string | null;
  action: string;
  target: string | null;
  outcome: "SUCCESS" | "FAILURE" | "DENIED";
  detail: string | null;
  clientIp: string | null;
  prevHash: string;
  rowHash: string;
}

export interface LedgerProblem {
  accountNo: string;
  txId: number | null;
  check: string;
  detail: string;
}

export interface LedgerVerification {
  checkedAt: string;
  accounts: number;
  transactions: number;
  valid: boolean;
  problems: LedgerProblem[];
}

export interface LedgerOperation {
  auditId: number;
  occurredAt: string;
  userId: string | null;
  userRole: string | null;
  action: string;
  clientIp: string | null;
  outcome: string;
}

export interface LedgerTraceItem {
  txId: number;
  txType: TxType;
  amount: number;
  balanceAfter: number;
  counterpartyAccountNo: string | null;
  memo: string | null;
  createdAt: string;
  rowHash: string | null;
  operations: LedgerOperation[];
  origin?: string;
}

export interface AuditVerification {
  valid: boolean;
  checked: number;
  brokenAt: number | null;
  reason: string | null;
}

export interface JobRun {
  jobName: string;
  runDate: string;
  startedAt: string;
  finishedAt: string | null;
  outcome: "SUCCESS" | "FAILURE" | null;
  detail: string | null;
}

export interface JobsInfo {
  enabled: boolean;
  startTime: string;
  runs: JobRun[];
}

export interface Account {
  accountNo: string;
  customerId: string;
  customerName: string | null;
  accountType: AccountType;
  balance: number;
  status: AccountStatus;
  updatedAt: string;
  openedAt: string | null;
  closedAt: string | null;
  /** Last customer-initiated transaction (interest credits do not count) */
  lastActivityAt: string | null;
  dormancyNotifiedAt: string | null;
  dormantAt: string | null;
  /** Amounts are in the currency's minor unit (JPY: yen, USD/EUR: cents) */
  currency: Currency;
  overdraftLimit: number;
  availableBalance: number;
}

export interface AccountTransaction {
  txId: number;
  accountNo: string;
  txType: TxType;
  amount: number;
  balanceAfter: number;
  counterpartyAccountNo: string | null;
  memo: string | null;
  createdAt: string;
}

export interface AccountDetail {
  account: Account;
  transactions: AccountTransaction[];
}

export interface TransactionPage {
  items: AccountTransaction[];
  page: number;
  size: number;
  total: number;
  hasNext: boolean;
  openingBalance: number;
  closingBalance: number;
  totalIn: number;
  totalOut: number;
}

export interface OperationResult {
  operation: "DEPOSIT" | "WITHDRAWAL" | "TRANSFER";
  accountNo: string;
  counterpartyAccountNo: string | null;
  amount: number;
  balanceAfter: number;
  executedAt: string;
}

export interface Customer {
  customerId: string;
  name: string;
  nameKana: string | null;
  birthDate: string | null;
  postalCode: string | null;
  address: string | null;
  phone: string | null;
  idDocType: IdDocType | null;
  idDocLast4: string | null;
  kycStatus: KycStatus;
  kycDecidedAt: string | null;
  kycNote: string | null;
  createdAt: string;
  registeredBy: string | null;
  customerType: "INDIVIDUAL" | "CORPORATE";
  kycDecidedBy: string | null;
  accounts: Account[];
}

export interface InterestResult {
  accountNo: string;
  accountType: AccountType | null;
  periodFrom: string | null;
  periodTo: string | null;
  days: number;
  balanceDays: number;
  averageBalance: number;
  grossInterest: number;
  nationalTax: number;
  localTax: number;
  tax: number;
  netInterest: number;
  settled: boolean;
  note: string | null;
}

export interface SettlementSummary {
  asOf: string;
  results: InterestResult[];
  settledCount: number;
  skippedCount: number;
  totalGross: number;
  totalTax: number;
  totalNet: number;
}

export interface CloseResult {
  accountNo: string;
  interest: InterestResult;
  payoutAmount: number;
  payoutAccountNo: string | null;
  closedAt: string;
}

export interface DormancyReport {
  asOf: string;
  noticeTargets: Account[];
  dormancyTargets: Account[];
  dormantAccounts: Account[];
  noticed: number;
  madeDormant: number;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  fieldErrors?: Record<string, string[]>;
  actionErrors?: string[];
}

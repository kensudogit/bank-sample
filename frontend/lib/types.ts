/** 口座種別：普通・貯蓄・当座・定期・外貨・通知 */
// API の応答・要求で使う型定義（Java 側の DTO に対応）。
// 金額は補助単位の整数（円、外貨はセント）、日時は ISO 形式の文字列で受け取る。

export type AccountType = "ORDINARY" | "SAVINGS" | "CHECKING" | "TIME" | "FOREIGN" | "NOTICE";
/** 取扱通貨 */
export type Currency = "JPY" | "USD" | "EUR";
/** 口座ステータス：利用可・凍結・休眠・解約 */
export type AccountStatus = "ACTIVE" | "FROZEN" | "DORMANT" | "CLOSED";
/** 取引種別（入出金明細の 1 行の種類） */
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
/** 本人確認（KYC）の状態 */
export type KycStatus = "PENDING" | "VERIFIED" | "REJECTED";
/** 本人確認書類の種類 */
export type IdDocType = "DRIVER_LICENSE" | "MY_NUMBER_CARD" | "PASSPORT" | "RESIDENCE_CARD";
/** 利用者の役割：顧客・窓口担当（テラー）・管理者（スーパーバイザー）。画面の表示・操作権限の判定に使う。 */
export type Role = "CUSTOMER" | "TELLER" | "SUPERVISOR";

/** ログイン中の利用者。mustChangePassword が true の間は初回パスワード変更を強制する。 */
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

/** ページング付き一覧の共通形 */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  hasNext: boolean;
}

/** 口座一覧（検索結果全体の残高合計付き） */
export interface AccountPage extends Page<Account> {
  totalBalance: number;
}

/** 監査ログ 1 件。prevHash/rowHash のハッシュチェーンで改ざんを検知できる。 */
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

/** 元帳検証で見つかった不整合 1 件 */
export interface LedgerProblem {
  accountNo: string;
  txId: number | null;
  check: string;
  detail: string;
}

/** 元帳（残高と取引履歴の整合性）検証の結果 */
export interface LedgerVerification {
  checkedAt: string;
  accounts: number;
  transactions: number;
  valid: boolean;
  problems: LedgerProblem[];
}

/** 取引に対応する操作記録（誰がどこから実行したか） */
export interface LedgerOperation {
  auditId: number;
  occurredAt: string;
  userId: string | null;
  userRole: string | null;
  action: string;
  clientIp: string | null;
  outcome: string;
}

/** 元帳追跡：取引 1 件と、それを発生させた操作の対応 */
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

/** 監査ログのハッシュチェーン検証結果（壊れている場合は位置と理由） */
export interface AuditVerification {
  valid: boolean;
  checked: number;
  brokenAt: number | null;
  reason: string | null;
}

/** 夜間ジョブの実行履歴 1 件 */
export interface JobRun {
  jobName: string;
  runDate: string;
  startedAt: string;
  finishedAt: string | null;
  outcome: "SUCCESS" | "FAILURE" | null;
  detail: string | null;
}

/** 夜間ジョブの設定（有効/無効・開始時刻）と実行履歴 */
export interface JobsInfo {
  enabled: boolean;
  startTime: string;
  runs: JobRun[];
}

/** 口座。availableBalance は当座貸越枠を含めた引き出し可能額。 */
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

/** 入出金明細 1 行。balanceAfter は取引後残高。 */
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

/** 口座詳細（口座情報と直近の取引） */
export interface AccountDetail {
  account: Account;
  transactions: AccountTransaction[];
}

/** 入出金明細の検索結果（期間の繰越残高・期末残高・入出金合計付き） */
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

/** 入金・出金・振込の実行結果 */
export interface OperationResult {
  operation: "DEPOSIT" | "WITHDRAWAL" | "TRANSFER";
  accountNo: string;
  counterpartyAccountNo: string | null;
  amount: number;
  balanceAfter: number;
  executedAt: string;
}

/** 顧客。本人確認書類番号は下 4 桁のみ保持・表示する。 */
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

/** 利息計算結果（平均残高・税引前利息・国税/地方税・税引後利息） */
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

/** 利息決算（一括付利）の集計結果 */
export interface SettlementSummary {
  asOf: string;
  results: InterestResult[];
  settledCount: number;
  skippedCount: number;
  totalGross: number;
  totalTax: number;
  totalNet: number;
}

/** 口座解約の結果（解約利息と払戻し先） */
export interface CloseResult {
  accountNo: string;
  interest: InterestResult;
  payoutAmount: number;
  payoutAccountNo: string | null;
  closedAt: string;
}

/** 休眠口座処理の結果（事前通知対象・休眠化対象・休眠済み口座） */
export interface DormancyReport {
  asOf: string;
  noticeTargets: Account[];
  dormancyTargets: Account[];
  dormantAccounts: Account[];
  noticed: number;
  madeDormant: number;
}

/** API エラー応答の本体 */
export interface ApiErrorBody {
  code: string;
  message: string;
  fieldErrors?: Record<string, string[]>;
  actionErrors?: string[];
}

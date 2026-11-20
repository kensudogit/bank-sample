import type { AccountStatus, AccountType, IdDocType, KycStatus, TxType } from "./types";

const yen = new Intl.NumberFormat("ja-JP", { style: "currency", currency: "JPY" });
const num = new Intl.NumberFormat("ja-JP");

export const formatYen = (n: number): string => yen.format(n);

export const formatNumber = (n: number): string => num.format(n);

export const formatDateTime = (iso: string | null | undefined): string =>
  iso ? iso.replace("T", " ").slice(0, 16) : "—";

export const formatDate = (iso: string | null | undefined): string => (iso ? iso.slice(0, 10) : "—");

/** Today's date (yyyy-MM-dd) in the browser's local time zone. */
export function todayIso(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export const accountTypeLabel: Record<AccountType, string> = {
  ORDINARY: "普通",
  SAVINGS: "貯蓄",
  CHECKING: "当座",
  TIME: "定期",
  FOREIGN: "外貨",
  NOTICE: "通知",
};

const minorDigits = (currency: string) => (currency === "JPY" ? 0 : 2);
const moneyFormats = new Map<string, Intl.NumberFormat>();

/** Formats an amount in the currency's minor unit (JPY yen, USD/EUR cents). */
export function formatMoney(minor: number, currency: string = "JPY"): string {
  if (currency === "JPY") return formatYen(minor);
  let f = moneyFormats.get(currency);
  if (!f) {
    f = new Intl.NumberFormat("ja-JP", { style: "currency", currency, minimumFractionDigits: 2 });
    moneyFormats.set(currency, f);
  }
  return f.format(minor / 10 ** minorDigits(currency));
}

export const productLabel: Record<string, string> = {
  HOUSING: "住宅ローン",
  BUSINESS: "事業融資",
  CARD: "カードローン",
  OVERDRAFT: "当座貸越",
  SYNDICATED: "シンジケートローン",
};

export const loanStatusLabel: Record<string, string> = {
  APPLIED: "申込受付",
  SCREENED: "審査済",
  APPROVED: "承認",
  REJECTED: "謝絶",
  CONTRACTED: "契約済",
  EXECUTED: "実行済",
  WITHDRAWN: "取下げ",
  ACTIVE: "正常返済中",
  DELINQUENT: "延滞",
  PAID_OFF: "完済",
  WRITTEN_OFF: "償却",
};

export const borrowerClassLabel: Record<string, string> = {
  NORMAL: "正常先",
  WATCH: "要注意先",
  SPECIAL: "要管理先",
  DOUBTFUL: "破綻懸念先",
  DEFACTO: "実質破綻先",
  BANKRUPT: "破綻先",
};

export const methodLabel: Record<string, string> = {
  EQUAL_PAYMENT: "元利均等",
  EQUAL_PRINCIPAL: "元金均等",
  REVOLVING: "リボルビング",
};

export const remitStatusLabel: Record<string, string> = {
  PENDING: "送信待ち",
  SENT: "送信済",
  SETTLED: "決済済",
  HELD: "保留",
  RETURNED: "返却・組戻し",
  CREDITED: "入金済",
  REJECTED: "謝絶",
  DUPLICATE: "重複（取込済み）",
};

export const amlRuleLabel: Record<string, string> = {
  LARGE_CASH: "大口現金取引",
  STRUCTURING: "分割入金（ストラクチャリング）",
  RAPID_MOVEMENT: "短期間の入出金（通過口座）",
  SANCTIONS: "制裁リスト該当の可能性",
  HIGH_RISK_COUNTRY: "高リスク国宛送金",
  FOREIGN_LARGE: "国外送金等調書の対象",
};

export const formatPercent = (ratio: number, digits = 1): string => `${(ratio * 100).toFixed(digits)}%`;

export const statusLabel: Record<AccountStatus, string> = {
  ACTIVE: "利用可",
  FROZEN: "凍結",
  DORMANT: "休眠",
  CLOSED: "解約",
};

export const txTypeLabel: Record<TxType, string> = {
  DEPOSIT: "入金",
  WITHDRAWAL: "出金",
  TRANSFER_OUT: "振込（出）",
  TRANSFER_IN: "振込（入）",
  INTEREST: "利息",
  TAX: "源泉税",
  CLOSE_PAYOUT: "解約払戻し",
  FX_OUT: "両替（出）",
  FX_IN: "両替（入）",
  LOAN_DISB: "融資入金",
  LOAN_REPAY: "融資返済",
  FEE: "手数料",
  DIRECT_DEBIT: "口座振替",
  REMIT_OUT: "送金",
  REMIT_IN: "振込入金",
  OD_INTEREST: "貸越利息",
};

/** Transaction types that increase the balance. */
export const creditTypes: ReadonlySet<TxType> = new Set<TxType>([
  "DEPOSIT",
  "TRANSFER_IN",
  "INTEREST",
  "FX_IN",
  "LOAN_DISB",
  "REMIT_IN",
]);

export const kycLabel: Record<KycStatus, string> = { PENDING: "確認待ち", VERIFIED: "確認済", REJECTED: "謝絶" };

export const idDocLabel: Record<IdDocType, string> = {
  DRIVER_LICENSE: "運転免許証",
  MY_NUMBER_CARD: "マイナンバーカード",
  PASSPORT: "パスポート",
  RESIDENCE_CARD: "在留カード",
};

/** Converts full-width digits and strips commas so "１０，０００" becomes "10000". */
export function normalizeAmount(input: string): string {
  return input
    .replace(/[０-９]/g, (d) => String.fromCharCode(d.charCodeAt(0) - 0xfee0))
    .replace(/[,，\s]/g, "");
}

/** Full-width digits / hyphens to half-width, spaces removed (phone, postal code). */
export function normalizeDigits(input: string): string {
  return input
    .replace(/[０-９]/g, (d) => String.fromCharCode(d.charCodeAt(0) - 0xfee0))
    .replace(/[－ー−‐]/g, "-")
    .replace(/\s/g, "");
}

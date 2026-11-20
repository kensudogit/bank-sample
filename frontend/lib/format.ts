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

export const accountTypeLabel: Record<AccountType, string> = { ORDINARY: "普通", SAVINGS: "貯蓄" };

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
};

/** Transaction types that increase the balance. */
export const creditTypes: ReadonlySet<TxType> = new Set<TxType>(["DEPOSIT", "TRANSFER_IN", "INTEREST"]);

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

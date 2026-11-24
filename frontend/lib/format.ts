// 画面表示用の書式・ラベル定義。
// 金額・日付の整形、API のコード値（口座種別・取引種別・融資ステータスなど）から日本語表示名への対応表、
// 全角数字を半角に揃える入力正規化をまとめる。

import type { AccountStatus, AccountType, IdDocType, KycStatus, TxType } from "./types";

// Intl.NumberFormat の生成は重いので、モジュール読み込み時に一度だけ作って使い回す
const yen = new Intl.NumberFormat("ja-JP", { style: "currency", currency: "JPY" });
const num = new Intl.NumberFormat("ja-JP");

/** 円建て金額を「￥1,234」形式で表示する。 */
export const formatYen = (n: number): string => yen.format(n);

/** 数値を 3 桁区切りで表示する。 */
export const formatNumber = (n: number): string => num.format(n);

/** ISO 日時を「yyyy-MM-dd HH:mm」で表示する（空なら「—」）。タイムゾーン変換はせず API の値をそのまま使う。 */
export const formatDateTime = (iso: string | null | undefined): string =>
  iso ? iso.replace("T", " ").slice(0, 16) : "—";

/** ISO 日付・日時の日付部分だけを表示する（空なら「—」）。 */
export const formatDate = (iso: string | null | undefined): string => (iso ? iso.slice(0, 10) : "—");

/** Today's date (yyyy-MM-dd) in the browser's local time zone. */
// toISOString() は UTC になり日本時間の深夜に前日になってしまうため、ローカル時刻から組み立てる
export function todayIso(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** 口座種別コード → 日本語表示名 */
export const accountTypeLabel: Record<AccountType, string> = {
  ORDINARY: "普通",
  SAVINGS: "貯蓄",
  CHECKING: "当座",
  TIME: "定期",
  FOREIGN: "外貨",
  NOTICE: "通知",
};

// 通貨ごとの補助単位の桁数（円は小数なし、外貨はセント単位で 2 桁）と、通貨別フォーマッタのキャッシュ
const minorDigits = (currency: string) => (currency === "JPY" ? 0 : 2);
const moneyFormats = new Map<string, Intl.NumberFormat>();

/** Formats an amount in the currency's minor unit (JPY yen, USD/EUR cents). */
// 金額は浮動小数の誤差を避けるため API では補助単位の整数で扱い、表示時にだけ小数に戻す
export function formatMoney(minor: number, currency: string = "JPY"): string {
  if (currency === "JPY") return formatYen(minor);
  let f = moneyFormats.get(currency);
  if (!f) {
    f = new Intl.NumberFormat("ja-JP", { style: "currency", currency, minimumFractionDigits: 2 });
    moneyFormats.set(currency, f);
  }
  return f.format(minor / 10 ** minorDigits(currency));
}

/** 融資商品コード → 日本語表示名 */
export const productLabel: Record<string, string> = {
  HOUSING: "住宅ローン",
  BUSINESS: "事業融資",
  CARD: "カードローン",
  OVERDRAFT: "当座貸越",
  SYNDICATED: "シンジケートローン",
};

/** 融資申込・融資契約のステータス → 日本語表示名 */
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

/** 債務者区分（金融庁の自己査定区分）→ 日本語表示名。貸倒引当金の計算に使われる。 */
export const borrowerClassLabel: Record<string, string> = {
  NORMAL: "正常先",
  WATCH: "要注意先",
  SPECIAL: "要管理先",
  DOUBTFUL: "破綻懸念先",
  DEFACTO: "実質破綻先",
  BANKRUPT: "破綻先",
};

/** 返済方式 → 日本語表示名 */
export const methodLabel: Record<string, string> = {
  EQUAL_PAYMENT: "元利均等",
  EQUAL_PRINCIPAL: "元金均等",
  REVOLVING: "リボルビング",
};

/** 振込・送金のステータス → 日本語表示名 */
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

/** AML（マネー・ローンダリング対策）の検知ルール → 日本語表示名 */
export const amlRuleLabel: Record<string, string> = {
  LARGE_CASH: "大口現金取引",
  STRUCTURING: "分割入金（ストラクチャリング）",
  RAPID_MOVEMENT: "短期間の入出金（通過口座）",
  SANCTIONS: "制裁リスト該当の可能性",
  HIGH_RISK_COUNTRY: "高リスク国宛送金",
  FOREIGN_LARGE: "国外送金等調書の対象",
};

/** 比率（0.123）をパーセント表示（12.3%）にする。 */
export const formatPercent = (ratio: number, digits = 1): string => `${(ratio * 100).toFixed(digits)}%`;

/** 口座ステータス → 日本語表示名 */
export const statusLabel: Record<AccountStatus, string> = {
  ACTIVE: "利用可",
  FROZEN: "凍結",
  DORMANT: "休眠",
  CLOSED: "解約",
};

/** 取引種別 → 日本語表示名（入出金明細の表示に使う） */
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
// 入金系の取引種別。明細で金額をプラス表示するか、マイナス表示するかの判定に使う。
export const creditTypes: ReadonlySet<TxType> = new Set<TxType>([
  "DEPOSIT",
  "TRANSFER_IN",
  "INTEREST",
  "FX_IN",
  "LOAN_DISB",
  "REMIT_IN",
]);

/** 本人確認（KYC）ステータス → 日本語表示名 */
export const kycLabel: Record<KycStatus, string> = { PENDING: "確認待ち", VERIFIED: "確認済", REJECTED: "謝絶" };

/** 本人確認書類の種類 → 日本語表示名 */
export const idDocLabel: Record<IdDocType, string> = {
  DRIVER_LICENSE: "運転免許証",
  MY_NUMBER_CARD: "マイナンバーカード",
  PASSPORT: "パスポート",
  RESIDENCE_CARD: "在留カード",
};

/** Converts full-width digits and strips commas so "１０，０００" becomes "10000". */
// 日本語 IME で全角入力された金額もそのまま受け付けるため、送信前に半角数字へ正規化する
export function normalizeAmount(input: string): string {
  return input
    .replace(/[０-９]/g, (d) => String.fromCharCode(d.charCodeAt(0) - 0xfee0))
    .replace(/[,，\s]/g, "");
}

/** Full-width digits / hyphens to half-width, spaces removed (phone, postal code). */
// 電話番号・郵便番号用。各種の全角ハイフン・長音記号もすべて半角ハイフンに揃える
export function normalizeDigits(input: string): string {
  return input
    .replace(/[０-９]/g, (d) => String.fromCharCode(d.charCodeAt(0) - 0xfee0))
    .replace(/[－ー−‐]/g, "-")
    .replace(/\s/g, "");
}

/** 情報系：顧客セグメント（RFM の R と M から判定） */
export const segmentLabel: Record<string, string> = {
  LOYAL: "優良",
  GROWTH: "成長",
  STANDARD: "一般",
  AT_RISK: "休眠リスク",
  LAPSED: "離反",
};

/** 顧客区分（個人・法人）→ 日本語表示名 */
export const customerTypeLabel: Record<string, string> = { INDIVIDUAL: "個人", CORPORATE: "法人" };

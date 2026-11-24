// lib/format.ts のテスト：金額・日付の整形、ラベル対応表、全角入力の正規化。
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  accountTypeLabel, borrowerClassLabel, creditTypes, formatDate, formatDateTime, formatMoney, formatNumber,
  formatPercent, formatYen, normalizeAmount, normalizeDigits, statusLabel, todayIso, txTypeLabel,
} from "@/lib/format";

describe("format", () => {
  afterEach(() => vi.useRealTimers());

  it("円・数値を 3 桁区切りで整形する", () => {
    expect(formatYen(1234567)).toMatch(/[￥¥]1,234,567/);
    expect(formatNumber(1234567)).toBe("1,234,567");
  });

  it("外貨は補助単位（セント）から小数 2 桁で表示する", () => {
    expect(formatMoney(123456, "USD")).toContain("1,234.56");
    expect(formatMoney(5000, "EUR")).toContain("50.00");
    expect(formatMoney(5000)).toMatch(/[￥¥]5,000/);
  });

  it("日時・日付を整形し、空なら — を返す", () => {
    expect(formatDateTime("2026-11-23T09:15:30")).toBe("2026-11-23 09:15");
    expect(formatDateTime(null)).toBe("—");
    expect(formatDate("2026-11-23T09:15:30")).toBe("2026-11-23");
    expect(formatDate(undefined)).toBe("—");
  });

  it("todayIso はローカル時刻の日付を返す", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 0, 5, 23, 59));
    expect(todayIso()).toBe("2026-01-05");
  });

  it("比率をパーセント表示にする", () => {
    expect(formatPercent(0.1234)).toBe("12.3%");
    expect(formatPercent(0.5, 0)).toBe("50%");
  });

  it("全角数字・カンマ・空白を除いて金額を正規化する", () => {
    expect(normalizeAmount("１０，０００")).toBe("10000");
    expect(normalizeAmount(" 1,234 ")).toBe("1234");
  });

  it("電話番号・郵便番号の全角数字とハイフン類を半角にする", () => {
    expect(normalizeDigits("０３－１２３４ー５６７８")).toBe("03-1234-5678");
    expect(normalizeDigits("１００−０００１")).toBe("100-0001");
  });

  it("ラベル対応表が主要コードを網羅している", () => {
    expect(accountTypeLabel.ORDINARY).toBe("普通");
    expect(statusLabel.DORMANT).toBe("休眠");
    expect(borrowerClassLabel.SPECIAL).toBe("要管理先");
    expect(txTypeLabel.REMIT_IN).toBe("振込入金");
  });

  it("入金系の取引種別だけが creditTypes に含まれる", () => {
    expect(creditTypes.has("DEPOSIT")).toBe(true);
    expect(creditTypes.has("INTEREST")).toBe(true);
    expect(creditTypes.has("WITHDRAWAL")).toBe(false);
    expect(creditTypes.has("FEE")).toBe(false);
  });
});

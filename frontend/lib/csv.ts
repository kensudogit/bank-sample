// 画面の表を CSV ファイルとしてダウンロードさせるユーティリティ。
// Excel で文字化けしないよう BOM 付き UTF-8 で出力し、CSV インジェクション（数式実行）も防ぐ。

/**
 * CSV download for tables (UTF-8 with BOM so that Excel opens Japanese text correctly).
 * Numbers are written as plain numbers; text cells are quoted when needed and neutralised against
 * spreadsheet formula injection (a leading = + - @ in free text becomes a literal).
 */
/**
 * 見出し行と明細行から CSV を組み立て、ブラウザにダウンロードさせる。
 * 改行は Excel に合わせて CRLF。Blob URL は使い終わったら即座に解放する。
 */
export function downloadCsv(filename: string, header: string[], rows: (string | number | null | undefined)[][]): void {
  const lines = [header.map(textCell).join(","), ...rows.map((r) => r.map(cell).join(","))];
  const blob = new Blob(["﻿" + lines.join("\r\n")], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

/** 1 セル分を CSV 用の文字列にする。null/undefined は空欄、数値・数値文字列は数値として出す。 */
function cell(v: string | number | null | undefined): string {
  if (v === null || v === undefined) return "";
  if (typeof v === "number") return Number.isFinite(v) ? String(v) : "";
  // Numeric strings from the API (e.g. BigDecimal "-1200") stay numbers, not text
  if (/^-?\d+(\.\d+)?$/.test(v)) return v;
  return textCell(v);
}

/**
 * 文字列セルをエスケープする。先頭が = + - @ などの場合は ' を付けて
 * 表計算ソフトに数式として解釈されないようにし（CSV インジェクション対策）、
 * カンマ・改行・ダブルクォートを含む場合はダブルクォートで囲む。
 */
function textCell(v: string): string {
  let s = v;
  if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`;
  return /[",\r\n]/.test(s) || s !== v ? `"${s.replace(/"/g, '""')}"` : s;
}

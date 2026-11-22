/**
 * CSV download for tables (UTF-8 with BOM so that Excel opens Japanese text correctly).
 * Numbers are written as plain numbers; text cells are quoted when needed and neutralised against
 * spreadsheet formula injection (a leading = + - @ in free text becomes a literal).
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

function cell(v: string | number | null | undefined): string {
  if (v === null || v === undefined) return "";
  if (typeof v === "number") return Number.isFinite(v) ? String(v) : "";
  // Numeric strings from the API (e.g. BigDecimal "-1200") stay numbers, not text
  if (/^-?\d+(\.\d+)?$/.test(v)) return v;
  return textCell(v);
}

function textCell(v: string): string {
  let s = v;
  if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`;
  return /[",\r\n]/.test(s) || s !== v ? `"${s.replace(/"/g, '""')}"` : s;
}

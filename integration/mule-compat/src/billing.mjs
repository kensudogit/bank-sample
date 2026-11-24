// 収納企業の請求データ（CSV）の検証と、勘定系の /billing-import の items への変換（Mule の dw/billing-csv.dwl と同じ規則）。

const HEADER = {
  "収納企業コード": "billerCode", "お客さま番号": "payerNumber", "金額": "amount", "引落日": "dueDate",
  billerCode: "billerCode", payerNumber: "payerNumber", amount: "amount", dueDate: "dueDate",
};

/** RFC 4180 の CSV を行・列に分ける（ダブルクォート内のカンマ・改行・"" に対応） */
export function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = "";
  let quoted = false;
  const s = text.replace(/^﻿/, "");
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (quoted) {
      if (ch === '"' && s[i + 1] === '"') { cell += '"'; i++; }
      else if (ch === '"') quoted = false;
      else cell += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ",") { row.push(cell); cell = ""; }
    else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && s[i + 1] === "\n") i++;
      row.push(cell); rows.push(row); row = []; cell = "";
    } else cell += ch;
  }
  if (cell !== "" || row.length > 0) { row.push(cell); rows.push(row); }
  return rows.filter((r) => r.some((c) => c.trim() !== ""));
}

const normDate = (d) => (/^[0-9]{8}$/.test(d) ? `${d.slice(0, 4)}-${d.slice(4, 6)}-${d.slice(6, 8)}` : d);

/** CSV を検証し、items と errors（行番号付き）を返す。errors が 1 件でもあれば取り込まない。 */
export function parseBilling(text) {
  const rows = parseCsv(text);
  const keys = (rows[0] ?? []).map((h) => HEADER[h.trim()] ?? h.trim());
  const items = rows.slice(1).map((r, i) => {
    const m = Object.fromEntries(keys.map((k, j) => [k, (r[j] ?? "").trim()]));
    return {
      line: i + 2,
      billerCode: m.billerCode ?? "",
      payerNumber: m.payerNumber ?? "",
      amount: (m.amount ?? "").replace(/,/g, ""),
      dueDate: normDate(m.dueDate ?? ""),
    };
  });
  const errors = [
    ...(items.length === 0 ? [{ line: 1, message: "データ行がありません" }] : []),
    ...(items.length > 1000 ? [{ line: 0, message: "1 回の取込は 1,000 行までです" }] : []),
    ...items.flatMap((it) => [
      ...(!/^[A-Z0-9]{1,8}$/.test(it.billerCode) ? [{ line: it.line, message: "収納企業コードが正しくありません" }] : []),
      ...(it.payerNumber === "" || it.payerNumber.length > 20 ? [{ line: it.line, message: "お客さま番号が正しくありません" }] : []),
      ...(!/^[1-9][0-9]{0,9}$/.test(it.amount) ? [{ line: it.line, message: "金額が正しくありません" }] : []),
      ...(!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(it.dueDate) ? [{ line: it.line, message: "引落日が正しくありません" }] : []),
    ]),
  ];
  return { items: items.map(({ line: _line, ...rest }) => rest), errors };
}

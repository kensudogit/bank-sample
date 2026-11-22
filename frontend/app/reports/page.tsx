"use client";

import { useState } from "react";
import { RequireRole } from "@/components/AuthProvider";
import { ErrorBox, Loading } from "@/components/Feedback";
import { useInfoReport, type ReportType, type Row } from "@/lib/banking";
import { downloadCsv } from "@/lib/csv";
import {
  accountTypeLabel, borrowerClassLabel, customerTypeLabel, formatNumber, formatYen, productLabel, segmentLabel, txTypeLabel,
} from "@/lib/format";

/** 情報系：帳票。種類と条件を選んで表示し、そのまま CSV に出力する。 */
export default function ReportsPage() {
  return (
    <RequireRole roles={["SUPERVISOR"]}>
      <Reports />
    </RequireRole>
  );
}

const REPORTS: { type: ReportType; label: string; period: boolean; note: string }[] = [
  { type: "DEPOSIT_BALANCE", label: "預金残高表", period: false, note: "基準日の預金種類別・通貨別の口座数と残高（外貨は仲値で円換算）" },
  { type: "LOAN_BALANCE", label: "貸出金残高表", period: false, note: "基準日の商品別・債務者区分別の件数と残高" },
  { type: "TX_SUMMARY", label: "取引集計表", period: true, note: "期間内の取引種別・通貨別の件数と金額（勘定系の明細を参照のみで集計）" },
  { type: "KPI_TREND", label: "経営指標 日次推移表", period: true, note: "期間内の日次の残高・顧客数・取引件数・収益" },
  { type: "CUSTOMER_ASSETS", label: "顧客別 預かり資産・RFM", period: false, note: "基準日の顧客別の預かり資産・借入・RFM（閲覧は監査ログに記録）" },
];

// 区分値の列は画面・CSV とも日本語のラベルで表示する
const labelers: Record<string, (v: string) => string> = {
  accountType: (v) => accountTypeLabel[v as keyof typeof accountTypeLabel] ?? v,
  product: (v) => productLabel[v] ?? v,
  borrowerClass: (v) => borrowerClassLabel[v] ?? v,
  txType: (v) => txTypeLabel[v as keyof typeof txTypeLabel] ?? v,
  segment: (v) => segmentLabel[v] ?? v,
  customerType: (v) => customerTypeLabel[v] ?? v,
};

function display(kind: string, v: unknown): string {
  if (v === null || v === undefined || v === "") return "—";
  if (kind === "yen") return formatYen(Number(v));
  if (kind === "number") return formatNumber(Number(v));
  return labelers[kind] ? labelers[kind](String(v)) : String(v);
}

function Reports() {
  const [type, setType] = useState<ReportType>("DEPOSIT_BALANCE");
  const [date, setDate] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const meta = REPORTS.find((r) => r.type === type)!;
  const q = meta.period ? { from: from || undefined, to: to || undefined } : { date: date || undefined };
  const { data, error, loading } = useInfoReport(type, q);

  const columns: Row[] = data?.columns ?? [];
  const rows: Row[] = data?.rows ?? [];
  const totals: Row = data?.totals ?? {};

  const exportCsv = () => {
    if (!data) return;
    const csvRows = rows.map((r) => columns.map((c) =>
      c.kind === "yen" || c.kind === "number" ? (r[c.key] ?? "") : labelers[c.kind] && r[c.key] != null ? labelers[c.kind](String(r[c.key])) : r[c.key]));
    if (Object.keys(totals).length > 0) {
      csvRows.push(columns.map((c, i) => (i === 0 ? "合計" : totals[c.key] ?? "")));
    }
    const cond = meta.period ? `${data.condition}`.replace(/[^\d〜-]/g, "").replace("〜", "_") : `${data.condition}`.replace(/[^\d-]/g, "");
    downloadCsv(`${type.toLowerCase()}_${cond || "latest"}.csv`, columns.map((c) => c.label), csvRows);
  };

  return (
    <>
      <div className="page-head">
        <div>
          <p className="eyebrow">Information systems · Reports</p>
          <h1>帳票</h1>
          <p>情報系（DWH）の日次データから作成します。表示内容はそのまま CSV（Excel 対応）に出力できます。</p>
        </div>
      </div>

      <section className="card">
        <div className="tabs" role="tablist" aria-label="帳票の種類" style={{ flexWrap: "wrap" }}>
          {REPORTS.map((r) => (
            <button key={r.type} type="button" role="tab" className="tab" aria-selected={type === r.type} onClick={() => setType(r.type)}>
              {r.label}</button>
          ))}
        </div>
        <p className="section-note">{meta.note}</p>
        <div className="filters">
          {meta.period ? (
            <>
              <label>開始日<input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
              <label>終了日<input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
              <span className="hint">空欄は最新の取込日までの 30 日</span>
            </>
          ) : (
            <>
              <label>基準日<input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></label>
              <span className="hint">空欄は最新の取込日（その日以前で最も新しい取込日を使用）</span>
            </>
          )}
          <button type="button" className="btn btn-secondary" onClick={exportCsv} disabled={!data || rows.length === 0}>
            CSV出力</button>
        </div>
        <ErrorBox error={error} />
      </section>

      {!data ? (!error && <Loading />) : (
        <section className="card">
          <h2>{data.title}</h2>
          <p className="section-note">{data.condition} ・ {rows.length} 行 ・ 作成 {String(data.generatedAt).replace("T", " ")}
            {loading ? "（更新中…）" : ""}</p>
          {rows.length === 0 ? <p className="muted">該当するデータがありません。</p> : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>{columns.map((c) => <th key={c.key} className={c.kind === "yen" || c.kind === "number" ? "num" : undefined}>{c.label}</th>)}</tr>
                </thead>
                <tbody>
                  {rows.map((r, i) => (
                    <tr key={i}>
                      {columns.map((c) => (
                        <td key={c.key} className={c.kind === "yen" || c.kind === "number" ? "num" : undefined}>{display(c.kind, r[c.key])}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
                {Object.keys(totals).length > 0 && (
                  <tfoot>
                    <tr>
                      {columns.map((c, i) => (
                        <th key={c.key} className={c.kind === "yen" || c.kind === "number" ? "num" : undefined}>
                          {i === 0 ? "合計" : totals[c.key] !== undefined ? display(c.kind, totals[c.key]) : ""}
                        </th>
                      ))}
                    </tr>
                  </tfoot>
                )}
              </table>
            </div>
          )}
        </section>
      )}
    </>
  );
}

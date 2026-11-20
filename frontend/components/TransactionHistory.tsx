"use client";

import { useState } from "react";
import { ErrorBox, Loading } from "@/components/Feedback";
import { bankApi, useTransactions, type TransactionQuery } from "@/lib/api";
import { creditTypes, formatDateTime, formatMoney, formatNumber, txTypeLabel } from "@/lib/format";
import type { AccountTransaction, TxType } from "@/lib/types";

const PAGE_SIZE = 20;
const ALL_TYPES = Object.keys(txTypeLabel) as TxType[];

interface Filter {
  from: string;
  to: string;
  types: TxType[];
}

/** 取引履歴: 期間・種別で絞り込み、ページ送り、期首／期末残高と入出金合計、CSV 出力。 */
export function TransactionHistory({ accountNo, currency = "JPY" }: { accountNo: string; currency?: string }) {
  const [draft, setDraft] = useState<Filter>({ from: "", to: "", types: [] });
  const [filter, setFilter] = useState<Filter>(draft);
  const [page, setPage] = useState(1);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<unknown>(null);

  const query: TransactionQuery = { accountNo, ...filter, page, size: PAGE_SIZE };
  const { data, error, loading } = useTransactions(query);

  const apply = (e: React.FormEvent) => {
    e.preventDefault();
    setFilter(draft);
    setPage(1);
  };

  const reset = () => {
    const empty: Filter = { from: "", to: "", types: [] };
    setDraft(empty);
    setFilter(empty);
    setPage(1);
  };

  const toggleType = (t: TxType) =>
    setDraft((d) => ({ ...d, types: d.types.includes(t) ? d.types.filter((x) => x !== t) : [...d.types, t] }));

  const exportCsv = async () => {
    setExporting(true);
    setExportError(null);
    try {
      const rows: AccountTransaction[] = [];
      // Page through the whole filtered range (capped so a huge history cannot hang the browser)
      for (let p = 1; p <= 50; p++) {
        const res = await bankApi.searchTransactions({ accountNo, ...filter, page: p, size: 200 });
        rows.push(...res.items);
        if (!res.hasNext) break;
      }
      downloadCsv(`transactions_${accountNo}_${filter.from || "all"}_${filter.to || "all"}.csv`, rows);
    } catch (err) {
      setExportError(err);
    } finally {
      setExporting(false);
    }
  };

  const lastPage = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <section className="card">
      <h2>取引履歴</h2>
      <form className="filters" onSubmit={apply}>
        <label>
          開始日
          <input type="date" value={draft.from} max={draft.to || undefined}
            onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        </label>
        <label>
          終了日
          <input type="date" value={draft.to} min={draft.from || undefined}
            onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        </label>
        <fieldset className="checks" style={{ border: 0, padding: 0, margin: 0 }}>
          <legend className="hint" style={{ padding: 0 }}>取引種別（未選択はすべて）</legend>
          {ALL_TYPES.map((t) => (
            <label key={t}>
              <input type="checkbox" checked={draft.types.includes(t)} onChange={() => toggleType(t)} />
              {txTypeLabel[t]}
            </label>
          ))}
        </fieldset>
        <div className="actions">
          <button className="btn btn-small" type="submit">検索</button>
          <button className="btn btn-secondary btn-small" type="button" onClick={reset}>条件クリア</button>
          <button className="btn btn-secondary btn-small" type="button" onClick={exportCsv}
            disabled={exporting || !data || data.total === 0}>
            {exporting ? "出力中…" : "CSV出力"}
          </button>
        </div>
      </form>

      <ErrorBox error={error ?? exportError} />
      {!data && !error && <Loading />}
      {data && (
        <>
          <dl className="stats">
            <div className="stat">
              <dt>{filter.from ? `期首残高（${filter.from} 開始時）` : "期首残高"}</dt>
              <dd>{filter.from ? formatMoney(data.openingBalance, currency) : "—"}</dd>
            </div>
            <div className="stat">
              <dt>入金合計</dt>
              <dd className="plus">{formatMoney(data.totalIn, currency)}</dd>
            </div>
            <div className="stat">
              <dt>出金合計</dt>
              <dd className="minus">{formatMoney(data.totalOut, currency)}</dd>
            </div>
            <div className="stat">
              <dt>{filter.to ? `期末残高（${filter.to} 終了時）` : "現在残高"}</dt>
              <dd>{formatMoney(data.closingBalance, currency)}</dd>
            </div>
          </dl>

          {data.items.length === 0 ? (
            <p className="empty">該当する明細はありません。</p>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>日時</th>
                    <th>取引</th>
                    <th className="num">金額</th>
                    <th className="num">取引後残高</th>
                    <th>相手口座</th>
                    <th>メモ</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((t) => {
                    const plus = creditTypes.has(t.txType);
                    return (
                      <tr key={t.txId}>
                        <td className="muted">{formatDateTime(t.createdAt)}</td>
                        <td>{txTypeLabel[t.txType]}</td>
                        <td className={`num ${plus ? "plus" : "minus"}`}>
                          {plus ? "+" : "−"}
                          {formatMoney(t.amount, currency)}
                        </td>
                        <td className="num">{formatMoney(t.balanceAfter, currency)}</td>
                        <td>{t.counterpartyAccountNo ?? ""}</td>
                        <td>{t.memo ?? ""}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}

          <div className="pager">
            <span className="muted">
              全 {formatNumber(data.total)} 件 ／ {page} / {lastPage} ページ{loading ? "（更新中…）" : ""}
            </span>
            <div className="actions">
              <button className="btn btn-secondary btn-small" type="button" disabled={page <= 1}
                onClick={() => setPage(page - 1)}>前へ</button>
              <button className="btn btn-secondary btn-small" type="button" disabled={!data.hasNext}
                onClick={() => setPage(page + 1)}>次へ</button>
            </div>
          </div>
        </>
      )}
    </section>
  );
}

function downloadCsv(filename: string, rows: AccountTransaction[]) {
  const header = ["取引ID", "日時", "取引種別", "入金額", "出金額", "取引後残高", "相手口座", "メモ"];
  const lines = rows.map((t) => {
    const plus = creditTypes.has(t.txType);
    return [
      t.txId,
      t.createdAt.replace("T", " "),
      txTypeLabel[t.txType],
      plus ? t.amount : "",
      plus ? "" : t.amount,
      t.balanceAfter,
      t.counterpartyAccountNo ?? "",
      t.memo ?? "",
    ].map(csvCell).join(",");
  });
  // BOM so that Excel opens the UTF-8 file with Japanese text correctly
  const blob = new Blob(["﻿" + [header.join(","), ...lines].join("\r\n")], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

/** Quotes a CSV cell and neutralises spreadsheet formulas (CSV injection) in free text. */
function csvCell(v: string | number): string {
  let s = String(v);
  if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`;
  return /[",\r\n]/.test(s) || s !== String(v) ? `"${s.replace(/"/g, '""')}"` : s;
}

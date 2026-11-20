"use client";

import { useState } from "react";
import { roleLabel } from "@/components/AuthProvider";
import { ErrorBox } from "@/components/Feedback";
import { bankApi } from "@/lib/api";
import { creditTypes, formatDateTime, formatMoney, txTypeLabel } from "@/lib/format";
import type { LedgerTraceItem, Role } from "@/lib/types";

/** 取引の追跡（行員向け）: 各明細を、それを実行した操作（誰が・いつ・どこから）と結び付けて表示する。 */
export function LedgerTrace({ accountNo, currency }: { accountNo: string; currency: string }) {
  const [items, setItems] = useState<LedgerTraceItem[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setItems(await bankApi.ledgerTrace(accountNo));
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="card">
      <h2>取引の追跡</h2>
      <p className="section-note">直近 50 件の明細と、その取引を実行した操作（監査ログ）を冪等キーで対応付けて表示します。</p>
      <ErrorBox error={error} />
      <div className="actions" style={{ marginBottom: items ? 12 : 0 }}>
        <button className="btn btn-secondary btn-small" type="button" onClick={load} disabled={loading}>
          {loading ? "読み込み中…" : items ? "再読み込み" : "追跡情報を表示"}
        </button>
      </div>
      {items && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th className="num">取引ID</th><th>日時</th><th>種類</th><th className="num">金額</th><th>実行した操作</th><th>ハッシュ</th></tr>
            </thead>
            <tbody>
              {items.map((t) => (
                <tr key={t.txId}>
                  <td className="num">{t.txId}</td>
                  <td className="muted">{formatDateTime(t.createdAt)}</td>
                  <td>{txTypeLabel[t.txType] ?? t.txType}</td>
                  <td className="num">{creditTypes.has(t.txType) ? "+" : "−"}{formatMoney(t.amount, currency)}</td>
                  <td>
                    {t.operations.length > 0
                      ? t.operations.map((o) => (
                          <div key={o.auditId}>
                            {o.userId ?? "—"}
                            {o.userRole && <span className="muted">（{roleLabel[o.userRole as Role] ?? o.userRole}）</span>}{" "}
                            <span className="mono">{o.action}</span>{" "}
                            <span className={`badge badge-${o.outcome}`}>{o.outcome}</span>{" "}
                            <span className="muted">{formatDateTime(o.occurredAt)} {o.clientIp ?? ""}</span>
                          </div>
                        ))
                      : <span className="muted">{t.origin ?? "操作記録なし"}</span>}
                  </td>
                  <td className="mono muted" title={t.rowHash ?? ""}>{t.rowHash ? t.rowHash.slice(0, 10) + "…" : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

"use client";

// AML（マネー・ローンダリング対策）・不正取引モニタリングの画面（/aml、管理者専用）。
// 自動検知されたアラートを調査し、「問題なし」か「疑わしい取引の届出」で完了させる。

import Link from "next/link";
import { useState } from "react";
import { RequireRole } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { riskApi, useAmlAlerts, type Row } from "@/lib/banking";
import { amlRuleLabel, formatDateTime } from "@/lib/format";

/** アラートの対応状況 → 日本語表示名 */
const statusLabel: Record<string, string> = { OPEN: "未対応", INVESTIGATING: "調査中", CLOSED: "問題なし", REPORTED: "届出済" };

/** AML アラート画面。管理者（SUPERVISOR）のみ利用できる。 */
export default function AmlPage() {
  return (
    <RequireRole roles={["SUPERVISOR"]}>
      <AmlAlerts />
    </RequireRole>
  );
}

/** 状態別のタブでアラートを一覧し、各アラートの調査結果を入力して状態を更新する。 */
function AmlAlerts() {
  const [status, setStatus] = useState("OPEN");
  const { data, error } = useAmlAlerts(status || undefined);
  const [err, setErr] = useState<unknown>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [notes, setNotes] = useState<Record<string, string>>({});

  // 各ボタン共通の実行ヘルパー：API を呼び、成功メッセージかエラーを表示する
  const run = async (fn: () => Promise<any>, done: (r: any) => string) => {
    setErr(null);
    setMessage(null);
    try {
      setMessage(done(await fn()));
    } catch (e) {
      setErr(e);
    }
  };

  return (
    <>
      <div className="page-head">
        <div>
          <p className="eyebrow">Compliance</p>
          <h1>AML・不正取引モニタリング</h1>
          <p>取引は10分ごとに自動で検査します。疑わしい取引は調査のうえ、届出（疑わしい取引の届出）または問題なしで完了します。</p>
        </div>
        <button className="btn btn-secondary" type="button"
          onClick={() => run(riskApi.scan, (r) => `検査しました: ${r.scanned} 件の取引、新しいアラート ${r.alerts} 件`)}>
          今すぐ検査
        </button>
      </div>
      <ErrorBox error={error ?? err} />
      {message && <SuccessBox>{message}</SuccessBox>}
      <div className="tabs" role="tablist">
        {[["OPEN", "未対応"], ["INVESTIGATING", "調査中"], ["REPORTED", "届出済"], ["CLOSED", "問題なし"], ["", "すべて"]].map(([s, l]) => (
          <button key={s} type="button" role="tab" className="tab" aria-selected={status === s} onClick={() => setStatus(s)}>{l}</button>
        ))}
      </div>
      {!data && !error && <Loading />}
      {data && data.length === 0 && <p className="empty card">該当するアラートはありません。</p>}
      {data && data.map((a: Row) => (
        <section className="card" key={a.alertId}>
          <div className="chart-head">
            <h2 style={{ margin: 0 }}>
              {amlRuleLabel[a.ruleCode] ?? a.ruleCode} <span className="muted" style={{ fontWeight: 500 }}>No.{a.alertId}</span>
            </h2>
            <span className={`badge badge-${a.status === "OPEN" ? "FAILURE" : a.status === "INVESTIGATING" ? "PENDING" : "SUCCESS"}`}>
              {statusLabel[a.status]}
            </span>
          </div>
          <dl className="stats">
            <div className="stat"><dt>スコア</dt><dd className={a.score >= 70 ? "minus" : ""}>{a.score}</dd></div>
            <div className="stat"><dt>お客さま</dt><dd>{a.customerId ? <Link href={`/customers/${a.customerId}`}>{a.customerName ?? a.customerId}</Link> : "—"}</dd></div>
            <div className="stat"><dt>口座</dt><dd>{a.accountNo ? <Link href={`/accounts/${a.accountNo}`}>{a.accountNo}</Link> : "—"}</dd></div>
            <div className="stat"><dt>検知</dt><dd style={{ fontSize: "0.85rem" }}>{formatDateTime(a.createdAt)}</dd></div>
          </dl>
          <p style={{ margin: "0 0 12px" }}>{a.detail}</p>
          {a.resolutionNote && <p className="section-note">対応: {a.resolutionNote}（{a.assignedTo}）</p>}
          {/* 未完了のアラートだけ、調査結果の入力欄と対応ボタンを出す */}
          {(a.status === "OPEN" || a.status === "INVESTIGATING") && (
            <form className="filters" onSubmit={(e) => e.preventDefault()}>
              <label style={{ flex: "1 1 320px" }}>
                調査結果・届出内容
                <input maxLength={400} value={notes[a.alertId] ?? ""} onChange={(e) => setNotes({ ...notes, [a.alertId]: e.target.value })} />
              </label>
              <div className="actions">
                {a.status === "OPEN" && (
                  <button className="btn btn-secondary btn-small" type="button"
                    onClick={() => run(() => riskApi.updateAlert(String(a.alertId), "INVESTIGATING"), () => "調査を開始しました")}>調査開始</button>
                )}
                <button className="btn btn-secondary btn-small" type="button"
                  onClick={() => run(() => riskApi.updateAlert(String(a.alertId), "CLOSED", notes[a.alertId]), () => "問題なしで完了しました")}>問題なし</button>
                <button className="btn btn-danger btn-small" type="button"
                  onClick={() => run(() => riskApi.updateAlert(String(a.alertId), "REPORTED", notes[a.alertId]), () => "届出済にしました")}>疑わしい取引の届出</button>
              </div>
            </form>
          )}
        </section>
      ))}
    </>
  );
}

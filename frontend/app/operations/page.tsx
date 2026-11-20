"use client";

import Link from "next/link";
import { useState } from "react";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { bankApi, useDormancyReport } from "@/lib/api";
import { accountTypeLabel, formatDate, formatYen, todayIso } from "@/lib/format";
import type { Account, SettlementSummary } from "@/lib/types";

/** 業務処理（バッチ）: 利息決算と休眠預金管理。本番では行員権限・二重承認・夜間ジョブ化が必要。 */
export default function OperationsPage() {
  return (
    <>
      <h1>業務処理</h1>
      <p className="section-note">
        本サンプルには行員認証がありません。本番では権限チェック・承認・夜間ジョブ化が必要な処理です。
      </p>
      <InterestSettlement />
      <DormancyManagement />
    </>
  );
}

function InterestSettlement() {
  const [asOf, setAsOf] = useState(todayIso);
  const [confirming, setConfirming] = useState(false);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [summary, setSummary] = useState<SettlementSummary | null>(null);

  const run = async () => {
    if (running) return;
    setRunning(true);
    setError(null);
    try {
      setSummary(await bankApi.settleInterest({ asOf }));
    } catch (err) {
      setError(err);
    } finally {
      setRunning(false);
      setConfirming(false);
    }
  };

  return (
    <section className="card">
      <h2>利息決算</h2>
      <p className="section-note">
        解約済み以外の全口座について、前回決算の翌日〜決算日の前日の利息を計算し、利息（税引前）と源泉税（国税15.315%・地方税5%）を記帳します。
        同じ期間を二重に決算することはありません。決算日は本日以前を指定してください。
      </p>
      <ErrorBox error={error} />
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setSummary(null); setConfirming(true); }}>
        <label>
          決算日
          <input type="date" value={asOf} max={todayIso()} required onChange={(e) => setAsOf(e.target.value)} />
        </label>
        <div className="actions">
          {!confirming ? (
            <button className="btn" type="submit">利息決算を実行…</button>
          ) : (
            <>
              <button className="btn btn-danger" type="button" onClick={run} disabled={running}>
                {running ? "決算中…" : `${asOf} で決算する`}
              </button>
              <button className="btn btn-secondary" type="button" onClick={() => setConfirming(false)} disabled={running}>
                やめる
              </button>
            </>
          )}
        </div>
      </form>

      {summary && (
        <>
          <SuccessBox>
            決算日 {summary.asOf}：決算 {summary.settledCount} 口座／対象外 {summary.skippedCount} 口座
          </SuccessBox>
          <dl className="stats">
            <div className="stat"><dt>利息合計（税引前）</dt><dd>{formatYen(summary.totalGross)}</dd></div>
            <div className="stat"><dt>源泉税合計</dt><dd className="minus">{formatYen(summary.totalTax)}</dd></div>
            <div className="stat"><dt>入金合計（税引後）</dt><dd className="plus">{formatYen(summary.totalNet)}</dd></div>
          </dl>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>口座番号</th>
                  <th>計算期間</th>
                  <th className="num">日数</th>
                  <th className="num">平均残高</th>
                  <th className="num">利息</th>
                  <th className="num">国税</th>
                  <th className="num">地方税</th>
                  <th className="num">受取利息</th>
                  <th>結果</th>
                </tr>
              </thead>
              <tbody>
                {summary.results.map((r) => (
                  <tr key={r.accountNo}>
                    <td><Link href={`/accounts/${r.accountNo}`}>{r.accountNo}</Link></td>
                    <td>{r.periodFrom ? `${r.periodFrom}〜${r.periodTo}` : "—"}</td>
                    <td className="num">{r.days}</td>
                    <td className="num">{formatYen(r.averageBalance)}</td>
                    <td className="num">{formatYen(r.grossInterest)}</td>
                    <td className="num">{formatYen(r.nationalTax)}</td>
                    <td className="num">{formatYen(r.localTax)}</td>
                    <td className="num">{formatYen(r.netInterest)}</td>
                    <td className={r.settled ? "" : "muted"}>{r.note}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  );
}

function DormancyManagement() {
  const [asOf, setAsOf] = useState(todayIso);
  const { data: report, error } = useDormancyReport(asOf);
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<unknown>(null);
  const [message, setMessage] = useState<string | null>(null);
  const isFuture = asOf > todayIso();

  const run = async () => {
    if (running) return;
    setRunning(true);
    setRunError(null);
    setMessage(null);
    try {
      const r = await bankApi.runDormancy({ asOf });
      setMessage(`休眠前通知 ${r.noticed} 件を記録し、${r.madeDormant} 口座を休眠にしました。`);
    } catch (err) {
      setRunError(err);
    } finally {
      setRunning(false);
    }
  };

  const pending = (report?.noticeTargets.length ?? 0) + (report?.dormancyTargets.length ?? 0);

  return (
    <section className="card">
      <h2>休眠預金管理</h2>
      <p className="section-note">
        最終異動（お客さまによる入出金・振込。利息の入金は含まない）から9年経過・残高1万円以上の口座に休眠前の通知を行い、
        10年経過かつ「残高1万円未満」または「通知から1か月経過」の口座を休眠にします。未来日を指定すると将来の対象を確認できます（実行は本日以前のみ）。
      </p>
      <ErrorBox error={error ?? runError} />
      {message && <SuccessBox>{message}</SuccessBox>}
      <form className="filters" onSubmit={(e) => e.preventDefault()}>
        <label>
          基準日
          <input type="date" value={asOf} required onChange={(e) => e.target.value && setAsOf(e.target.value)} />
        </label>
        <div className="actions">
          <button className="btn" type="button" onClick={run} disabled={running || isFuture || pending === 0}>
            {running ? "処理中…" : "通知記録・休眠化を実行"}
          </button>
        </div>
      </form>
      {!report && !error && <Loading />}
      {report && (
        <>
          <DormancyTable title="休眠前の通知対象（最終異動から9年以上・残高1万円以上・未通知）" accounts={report.noticeTargets} />
          <DormancyTable title="休眠化の対象（最終異動から10年以上）" accounts={report.dormancyTargets} />
          <DormancyTable title="休眠中の口座" accounts={report.dormantAccounts} showDormantAt />
        </>
      )}
    </section>
  );
}

function DormancyTable({ title, accounts, showDormantAt }: { title: string; accounts: Account[]; showDormantAt?: boolean }) {
  return (
    <div className="table-wrap" style={{ marginBottom: 16 }}>
      <h3 style={{ fontSize: "0.95rem", margin: "8px 0" }}>
        {title}：{accounts.length} 件
      </h3>
      {accounts.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>口座番号</th>
              <th>名義</th>
              <th>種別</th>
              <th className="num">残高</th>
              <th>最終異動日</th>
              <th>通知日</th>
              {showDormantAt && <th>休眠日</th>}
            </tr>
          </thead>
          <tbody>
            {accounts.map((a) => (
              <tr key={a.accountNo}>
                <td><Link href={`/accounts/${a.accountNo}`}>{a.accountNo}</Link></td>
                <td>{a.customerName}</td>
                <td>{accountTypeLabel[a.accountType]}</td>
                <td className="num">{formatYen(a.balance)}</td>
                <td>{formatDate(a.lastActivityAt)}</td>
                <td>{formatDate(a.dormancyNotifiedAt)}</td>
                {showDormantAt && <td>{formatDate(a.dormantAt)}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

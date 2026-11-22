"use client";

import Link from "next/link";
import { useState } from "react";
import { RequireRole } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { bankApi, useDormancyReport, useJobs } from "@/lib/api";
import { loanApi, useProvisionSummary, type Row } from "@/lib/banking";
import { accountTypeLabel, formatDate, formatDateTime, formatYen, todayIso } from "@/lib/format";
import type { Account, SettlementSummary } from "@/lib/types";

/** 業務処理（バッチ）: 利息決算と休眠預金管理。本番では行員権限・二重承認・夜間ジョブ化が必要。 */
export default function OperationsPage() {
  return (
    <RequireRole roles={["SUPERVISOR"]}>
      <h1>業務処理</h1>
      <p className="section-note">役席者のみ実行できます。実行内容は監査ログに記録されます。</p>
      <NightlyJobs />
      <LoanProvisions />
      <InterestSettlement />
      <DormancyManagement />
    </RequireRole>
  );
}

const jobLabel: Record<string, string> = {
  DORMANCY: "休眠管理",
  INTEREST_SETTLEMENT: "利息決算",
  REQUEST_CLEANUP: "冪等キー整理",
  DW_LOAD: "情報系取込",
};

function NightlyJobs() {
  const { data, error } = useJobs();
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<unknown>(null);
  const [ran, setRan] = useState<string[] | null>(null);

  const runNow = async () => {
    if (running) return;
    setRunning(true);
    setRunError(null);
    try {
      setRan(await bankApi.runJobs());
    } catch (err) {
      setRunError(err);
    } finally {
      setRunning(false);
    }
  };

  return (
    <section className="card">
      <h2>夜間バッチ</h2>
      <p className="section-note">
        毎日 {data?.startTime ?? "01:00"} 以降に自動実行：休眠管理・古い冪等キーの整理（90日超）。3/1・9/1 は利息決算（2月末・8月末まで）。
        同じジョブは1日1回だけ実行されます。
        {data && !data.enabled && <strong>（自動実行は無効です: BANK_JOBS_ENABLED）</strong>}
      </p>
      <ErrorBox error={error ?? runError} />
      {ran && (
        <SuccessBox>
          {ran.length === 0
            ? "本日実行すべきジョブはありません（実行済みまたは開始時刻前）。"
            : `実行しました: ${ran.map((j) => jobLabel[j] ?? j).join("、")}`}
        </SuccessBox>
      )}
      <div className="actions" style={{ marginBottom: 12 }}>
        <button className="btn btn-secondary btn-small" type="button" onClick={runNow} disabled={running}>
          {running ? "実行中…" : "未実行のジョブを今すぐ実行"}
        </button>
      </div>
      {data && data.runs.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>業務日</th>
                <th>ジョブ</th>
                <th>開始</th>
                <th>終了</th>
                <th>結果</th>
                <th>内容</th>
              </tr>
            </thead>
            <tbody>
              {data.runs.map((r) => (
                <tr key={`${r.jobName}-${r.runDate}`}>
                  <td>{r.runDate}</td>
                  <td>{jobLabel[r.jobName] ?? r.jobName}</td>
                  <td className="muted">{formatDateTime(r.startedAt)}</td>
                  <td className="muted">{formatDateTime(r.finishedAt)}</td>
                  <td>{r.outcome ? <span className={`badge badge-${r.outcome}`}>{r.outcome}</span> : "実行中"}</td>
                  <td>{r.detail ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
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

const classLabel: Record<string, string> = {
  NORMAL: "正常先", WATCH: "要注意先", SPECIAL: "要管理先", DOUBTFUL: "破綻懸念先", DEFACTO: "実質破綻先", BANKRUPT: "破綻先",
};

/** 貸倒引当金：毎晩の約定返済処理（融資）の後に自動で見直す。手動での見直しと区分別の残高。 */
function LoanProvisions() {
  const { data, error } = useProvisionSummary();
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<unknown>(null);
  const [result, setResult] = useState<Row | null>(null);
  const runNow = async () => {
    if (running) return;
    setRunning(true);
    setRunError(null);
    try {
      setResult(await loanApi.provisionRun());
    } catch (err) {
      setRunError(err);
    } finally {
      setRunning(false);
    }
  };
  return (
    <section className="card">
      <h2>貸倒引当金</h2>
      <p className="section-note">
        債務者区分ごとに必要額を計算し、差額を積み増し・取り崩します（正常先 0.5%・要注意先 3%・要管理先 15%・破綻懸念先は非保全額の 70%・
        実質破綻先以下は 100%）。夜間バッチ（約定返済）の後に自動で実行されます。同じ日に何度実行しても結果は同じです。
      </p>
      <ErrorBox error={error ?? runError} />
      {result && (
        <SuccessBox>
          {result.loans} 件を見直し、{result.changed} 件を更新しました（増減 {formatYen(Number(result.totalChange))}・引当総額 {formatYen(Number(result.totalProvision))}）。
        </SuccessBox>
      )}
      {data && (
        <div className="table-wrap" style={{ marginBottom: 12 }}>
          <table>
            <thead><tr><th>債務者区分</th><th className="num">件数</th><th className="num">引当額</th></tr></thead>
            <tbody>
              {(data.byClass as Row[]).map((r) => (
                <tr key={r.borrowerClass}><td>{classLabel[r.borrowerClass] ?? r.borrowerClass}</td>
                  <td className="num">{r.loans}</td><td className="num">{formatYen(Number(r.provision))}</td></tr>
              ))}
              <tr><th>合計</th><th /><th className="num">{formatYen(Number(data.totalProvision))}</th></tr>
            </tbody>
          </table>
        </div>
      )}
      <button className="btn btn-secondary btn-small" type="button" onClick={runNow} disabled={running}>
        {running ? "見直し中…" : "引当を今すぐ見直す"}
      </button>
    </section>
  );
}

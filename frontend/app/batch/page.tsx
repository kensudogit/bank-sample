"use client";

// 銀行バッチの画面（/batch、管理者専用）。夜間バッチの業務一覧と直近結果（失敗時の再実行）、
// 業務日ごとの照合結果・取引集計・総勘定元帳日計・利息計上・手数料照合、月末締めの状況、帳票 CSV のダウンロードを行う。

import { useState } from "react";
import { RequireRole } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { bankApi } from "@/lib/api";
import { batchApi, useBatchCatalog, useBatchDaily, useMonthCloses, useReportOutputs, type Row } from "@/lib/banking";
import { formatDate, formatDateTime, formatNumber, formatYen, todayIso, txTypeLabel } from "@/lib/format";

/** 銀行バッチ：業務一覧（直近の結果・失敗の再実行）、日次の結果（集計・利息・手数料・照合・元帳）、月末締め、帳票出力。 */
// 4 つのタブ（業務一覧・日次の結果・月末締め・帳票出力）で切り替える
export default function BatchPage() {
  const [tab, setTab] = useState<"catalog" | "daily" | "month" | "reports">("catalog");
  return (
    <RequireRole roles={["SUPERVISOR"]}>
      <div className="page-head">
        <div>
          <p className="eyebrow">Batch operations</p>
          <h1>銀行バッチ</h1>
          <p>毎日 01:00 以降に前日分を処理します。同じジョブは 1 日 1 回。照合で NG が出たジョブは失敗として記録され、原因を直して再実行できます。</p>
        </div>
      </div>
      <div className="tabs" role="tablist">
        {([["catalog", "業務一覧"], ["daily", "日次の結果"], ["month", "月末締め"], ["reports", "帳票出力"]] as const).map(([t, label]) => (
          <button key={t} type="button" role="tab" className="tab" aria-selected={tab === t} onClick={() => setTab(t)}>{label}</button>
        ))}
      </div>
      {tab === "catalog" && <Catalog />}
      {tab === "daily" && <Daily />}
      {tab === "month" && <MonthCloses />}
      {tab === "reports" && <Reports />}
    </RequireRole>
  );
}

/** ジョブの直近結果（成功・失敗・実行中・未実行）をバッジで表示する。 */
function Outcome({ last }: { last: Row | null }) {
  if (!last) return <span className="muted">未実行</span>;
  const cls = last.outcome === "SUCCESS" ? "badge-SUCCESS" : last.outcome === "FAILURE" ? "badge-FAILURE" : "badge-PENDING";
  const label = last.outcome === "SUCCESS" ? "成功" : last.outcome === "FAILURE" ? "失敗" : "実行中";
  return <span className={`badge ${cls}`}>{label}</span>;
}

/** 業務一覧：業務ごとのジョブ、実行タイミング、直近の結果。失敗したジョブはその業務日で再実行できる。 */
function Catalog() {
  const { data, error } = useBatchCatalog();
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  // ボタン共通の実行ヘルパー。busy の間は二重に実行しない。
  const act = async (fn: () => Promise<unknown>, done: (r: any) => string) => {
    if (busy) return;
    setBusy(true);
    setErr(null);
    setMsg(null);
    try {
      setMsg(done(await fn()));
    } catch (e) {
      setErr(e);
    } finally {
      setBusy(false);
    }
  };

  if (!data) return error ? <ErrorBox error={error} /> : <Loading />;
  // ジョブ 1 件分の表示（業務一覧と「そのほかの夜間ジョブ」で共通）
  const jobRow = (j: Row, key: string) => (
    <div key={key} className="job-line">
      <span><strong>{j.label}</strong> <code className="muted">{j.name}</code></span>
      <span className="muted">{j.timing}</span>
      <span><Outcome last={j.last} /> {j.last && <span className="muted">{formatDate(j.last.runDate)} {formatDateTime(j.last.finishedAt).slice(11)}</span>}</span>
      {j.last?.outcome === "FAILURE" && (
        <button type="button" className="btn btn-secondary btn-small" disabled={busy}
          onClick={() => act(() => batchApi.rerun(j.name, String(j.last.runDate).slice(0, 10)),
            (r) => `${j.label} を再実行しました：${r.outcome === "SUCCESS" ? "成功" : "失敗"}（${r.detail ?? ""}）`)}>
          再実行</button>
      )}
      {j.last?.detail && <span className={`job-detail ${j.last.outcome === "FAILURE" ? "minus" : "muted"}`}>{j.last.detail}</span>}
    </div>
  );

  return (
    <>
      <ErrorBox error={err} />
      {msg && <SuccessBox>{msg}</SuccessBox>}
      <section className="card">
        <div className="chart-head">
          <h2 style={{ margin: 0 }}>銀行バッチの業務</h2>
          <button type="button" className="btn btn-secondary btn-small" disabled={busy}
            onClick={() => act(() => bankApi.runJobs(), (r: string[]) => r.length === 0
              ? "本日実行すべきジョブはありません（実行済みまたは開始時刻前）。" : `実行しました：${r.join("、")}`)}>
            未実行のジョブを今すぐ実行</button>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>#</th><th>業務</th><th>主な処理内容</th><th style={{ minWidth: 420 }}>ジョブ・タイミング・直近の結果</th></tr></thead>
            <tbody>
              {(data.items as Row[]).map((it) => (
                <tr key={it.no}>
                  <td>{it.no}</td>
                  <td><strong>{it.business}</strong></td>
                  <td className="muted" style={{ whiteSpace: "normal" }}>{it.description}</td>
                  <td style={{ whiteSpace: "normal" }}>{(it.jobs as Row[]).map((j) => jobRow(j, `${it.no}-${j.name}`))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <section className="card">
        <h2>そのほかの夜間ジョブ</h2>
        {(data.otherJobs as Row[]).map((j) => jobRow(j, j.name))}
      </section>
    </>
  );
}

/** 前日の日付（yyyy-MM-dd）。日次バッチは前日分を処理するため、業務日の既定値に使う。 */
function yesterday(): string {
  const d = new Date(`${todayIso()}T00:00:00`);
  d.setDate(d.getDate() - 1);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** 日次の結果：選んだ業務日のバッチ結果（照合・集計・元帳・利息・手数料）を表示する。 */
function Daily() {
  const [date, setDate] = useState(yesterday());
  const { data, error, loading } = useBatchDaily(date);
  const checks: Row[] = data?.checks ?? [];
  const ledger: Row[] = data?.ledger ?? [];
  // 総勘定元帳の借方・貸方の合計（一致していれば貸借が合っている）
  const dr = ledger.reduce((s, r) => s + Number(r.debit), 0);
  const cr = ledger.reduce((s, r) => s + Number(r.credit), 0);
  return (
    <>
      <div className="filters">
        <label>業務日<input type="date" value={date} max={yesterday()} onChange={(e) => setDate(e.target.value)} /></label>
        {loading && <span className="hint">更新中…</span>}
      </div>
      <ErrorBox error={error} />
      {!data ? (!error && <Loading />) : (
        <>
          <section className="card">
            <h2>照合結果</h2>
            {checks.length === 0 ? <p className="muted">この日のバッチ結果はまだありません（夜間バッチは翌日 01:00 以降に前日分を処理します）。</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>区分</th><th>項目</th><th>結果</th><th>内容</th></tr></thead>
                  <tbody>
                    {checks.map((c) => (
                      <tr key={`${c.checkGroup}-${c.checkItem}`}>
                        <td>{groupLabel[c.checkGroup] ?? c.checkGroup}</td>
                        <td style={{ whiteSpace: "normal" }}>{c.checkItem}</td>
                        <td><span className={`badge ${c.result === "OK" ? "badge-SUCCESS" : c.result === "NG" ? "badge-FAILURE" : "badge-PENDING"}`}>{c.result}</span></td>
                        <td className="muted" style={{ whiteSpace: "normal" }}>{c.detail}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
          <div className="grid-2">
            <section className="card table-wrap">
              <h2>日次取引集計</h2>
              <table>
                <thead><tr><th>取引種別</th><th>通貨</th><th className="num">件数</th><th className="num">金額</th></tr></thead>
                <tbody>
                  {(data.summary as Row[]).map((r) => (
                    <tr key={`${r.txType}-${r.currency}`}><td>{txTypeLabel[r.txType as keyof typeof txTypeLabel] ?? r.txType}</td>
                      <td>{r.currency}</td><td className="num">{formatNumber(r.txCount)}</td><td className="num">{formatNumber(r.amount)}</td></tr>
                  ))}
                </tbody>
              </table>
            </section>
            <section className="card table-wrap">
              <h2>総勘定元帳 日計</h2>
              <table>
                <thead><tr><th>科目</th><th className="num">借方</th><th className="num">貸方</th></tr></thead>
                <tbody>
                  {ledger.map((r) => (
                    <tr key={r.glCode}><td>{r.glCode} {r.name}</td><td className="num">{formatYen(r.debit)}</td><td className="num">{formatYen(r.credit)}</td></tr>
                  ))}
                  {ledger.length > 0 && <tr><th>合計</th><th className="num">{formatYen(dr)}</th><th className="num">{formatYen(cr)}</th></tr>}
                </tbody>
              </table>
            </section>
            <section className="card table-wrap">
              <h2>預金利息の日次計上</h2>
              <table>
                <thead><tr><th>種類</th><th className="num">口座</th><th className="num">当日の利息</th><th className="num">未払利息（累計）</th></tr></thead>
                <tbody>
                  {(data.interest as Row[]).map((r) => (
                    <tr key={r.accountType}><td>{typeLabel[r.accountType] ?? r.accountType}</td><td className="num">{r.accounts}</td>
                      <td className="num">{Number(r.daily).toFixed(2)} 円</td><td className="num">{formatYen(Math.floor(Number(r.cumulative)))}</td></tr>
                  ))}
                </tbody>
              </table>
            </section>
            <section className="card table-wrap">
              <h2>手数料の計算・照合</h2>
              <table>
                <thead><tr><th>種類</th><th className="num">件数</th><th className="num">計上額</th><th className="num">料金表</th><th className="num">不一致</th></tr></thead>
                <tbody>
                  {(data.fees as Row[]).map((r) => (
                    <tr key={r.feeType}><td>{r.feeType === "DOMESTIC_TRANSFER" ? "他行振込" : "海外送金"}</td><td className="num">{r.feeCount}</td>
                      <td className="num">{formatYen(r.chargedAmount)}</td><td className="num">{formatYen(r.expectedAmount)}</td>
                      <td className={`num ${Number(r.mismatchCount) > 0 ? "minus" : ""}`}>{r.mismatchCount}</td></tr>
                  ))}
                </tbody>
              </table>
            </section>
          </div>
        </>
      )}
    </>
  );
}

/** 照合の区分 → 日本語表示名 */
const groupLabel: Record<string, string> = { FEE: "手数料", REMITTANCE: "為替照合", LEDGER: "会計照合" };
/** 利息計上の対象となる預金種類 → 日本語表示名 */
const typeLabel: Record<string, string> = { ORDINARY: "普通", SAVINGS: "貯蓄", NOTICE: "通知", TIME: "定期" };

/** 月末締めの一覧。照合 NG が残る月は「例外」として表示する。 */
function MonthCloses() {
  const { data, error } = useMonthCloses();
  if (!data) return error ? <ErrorBox error={error} /> : <Loading />;
  return (
    <section className="card table-wrap">
      <h2>月末締め</h2>
      <p className="section-note">毎月 1 日の夜間バッチで前月を締めます。その月の照合に NG が残っていれば「例外」とし、原因を直してジョブを再実行すると置き換わります。</p>
      {data.length === 0 ? <p className="muted">まだ締めた月はありません。</p> : (
        <table>
          <thead><tr><th>月</th><th>状態</th><th className="num">月末 預金</th><th className="num">月末 貸出金</th><th className="num">口座数</th>
            <th className="num">取引件数</th><th className="num">手数料</th><th className="num">貸出金利息</th><th className="num">預金利息</th>
            <th className="num">未払利息</th><th>締め</th></tr></thead>
          <tbody>
            {data.map((m) => (
              <tr key={m.closeMonth}>
                <td>{m.closeMonth}</td>
                <td><span className={`badge ${m.status === "CLOSED" ? "badge-SUCCESS" : "badge-FAILURE"}`}>{m.status === "CLOSED" ? "締め済み" : `例外（NG ${m.ngChecks}）`}</span></td>
                <td className="num">{formatYen(m.totalDeposits)}</td><td className="num">{formatYen(m.totalLoans)}</td>
                <td className="num">{m.accountCount}</td><td className="num">{formatNumber(m.txCount)}</td>
                <td className="num">{formatYen(m.feeIncome)}</td><td className="num">{formatYen(m.loanInterest)}</td>
                <td className="num">{formatYen(m.interestPaid)}</td><td className="num">{formatYen(m.accruedInterest)}</td>
                <td className="muted">{formatDateTime(m.closedAt)}（{m.closedBy}）</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

/** バッチが作成する帳票の種類 → 日本語表示名 */
const reportLabel: Record<string, string> = {
  TX_JOURNAL: "取引明細表", DAILY_BALANCE: "日計表", MONTH_BALANCES: "月末残高一覧", MONTH_SUMMARY: "月次集計表",
};

/** 帳票出力の一覧と CSV ダウンロード（ダウンロードはサーバー側で監査ログに記録される）。 */
function Reports() {
  const { data, error } = useReportOutputs();
  const [err, setErr] = useState<unknown>(null);
  // 一覧には本文を含めず、ダウンロード時に 1 件分の本文を取得して Blob としてダウンロードさせる
  const download = async (r: Row) => {
    setErr(null);
    try {
      const full = await batchApi.report(String(r.reportId));
      const blob = new Blob([full.content], { type: "text/csv;charset=utf-8" });   // 本文は BOM 付き UTF-8
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = r.fileName;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setErr(e);
    }
  };
  if (!data) return error ? <ErrorBox error={error} /> : <Loading />;
  return (
    <section className="card table-wrap">
      <h2>帳票出力</h2>
      <p className="section-note">夜間バッチが前日分の取引明細表・日計表を、月初は前月の月末残高一覧・月次集計表を作成します（CSV・Excel 対応）。ダウンロードは監査ログに記録されます。残高証明書は各口座の画面から随時発行できます。</p>
      <ErrorBox error={err} />
      {data.length === 0 ? <p className="muted">まだ帳票はありません。</p> : (
        <table>
          <thead><tr><th>業務日</th><th>帳票</th><th>ファイル</th><th className="num">行数</th><th>作成</th><th /></tr></thead>
          <tbody>
            {data.map((r) => (
              <tr key={r.reportId}>
                <td>{formatDate(r.businessDate)}</td><td>{reportLabel[r.reportType] ?? r.reportType}</td>
                <td><code>{r.fileName}</code></td><td className="num">{formatNumber(r.rowCount)}</td>
                <td className="muted">{formatDateTime(r.createdAt)}（{r.createdBy}）</td>
                <td><button type="button" className="btn btn-secondary btn-small" onClick={() => download(r)}>CSV</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

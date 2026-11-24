"use client";

// 経営ダッシュボードの画面（/mis、管理者専用）。情報系（DWH）の日次スナップショットから、
// 主要指標・残高推移・期間損益・残高構成・月次サマリーを表示し、DWH への取込（バックフィル）も行う。

import { useState } from "react";
import { RequireRole } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { TrendChart } from "@/components/TrendChart";
import { infoApi, useMisDashboard, type Row } from "@/lib/banking";
import { invalidate } from "@/lib/query";
import {
  accountTypeLabel, borrowerClassLabel, formatDateTime, formatNumber, formatPercent, formatYen, productLabel, segmentLabel, todayIso,
} from "@/lib/format";

/** 情報系：経営ダッシュボード（MIS）。DWH の日次スナップショットだけを読む。 */
export default function MisPage() {
  return (
    <RequireRole roles={["SUPERVISOR"]}>
      <MisDashboard />
    </RequireRole>
  );
}

/** 選べる表示期間（最新の取込日から遡る日数） */
const RANGES = [
  { days: 30, label: "30日" },
  { days: 90, label: "90日" },
  { days: 180, label: "180日" },
] as const;

/** yyyy-MM-dd に日数を足す（ローカル時刻で計算し、yyyy-MM-dd で返す）。 */
function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00`);
  d.setDate(d.getDate() + days);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** ダッシュボード本体。期間を切り替えても、新しい結果が届くまで前の表示を続ける。 */
function MisDashboard() {
  const [days, setDays] = useState<number>(30);
  // まず期間指定なしで取得して最新の取込日を知り、そこから表示期間の開始日を決めて取得し直す
  const probe = useMisDashboard();
  const lastDate: string | undefined = probe.data?.status?.lastDate ?? undefined;
  const from = lastDate ? addDays(lastDate, -(days - 1)) : undefined;
  const { data, error, loading } = useMisDashboard(from, lastDate);
  const view = data ?? probe.data;

  if (!view) return probe.error ? <ErrorBox error={probe.error} /> : <Loading />;
  const latest: Row | null = view.latest;
  const prev: Row | null = view.previous ?? null;
  const series: Row[] = view.series ?? [];

  return (
    <>
      <div className="page-head">
        <div>
          <p className="eyebrow">Information systems · MIS</p>
          <h1>経営ダッシュボード</h1>
          <p>
            {latest ? `基準日 ${latest.snapshotDate}（${view.from} 〜 ${view.to}）` : "情報系のデータがまだ取り込まれていません"}
            {loading ? "（更新中…）" : ""}
          </p>
        </div>
        <div className="tabs" role="tablist" aria-label="表示期間" style={{ marginBottom: 0 }}>
          {RANGES.map((r) => (
            <button key={r.days} type="button" role="tab" className="tab" aria-selected={days === r.days}
              onClick={() => setDays(r.days)}>{r.label}</button>
          ))}
        </div>
      </div>
      <ErrorBox error={error} />

      {latest && (
        <>
          {/* 主要指標（前日比は直前の取込日との比較） */}
          <section className="card">
            <h2>主要指標</h2>
            <dl className="stats">
              <Kpi label="預金残高（外貨は仲値換算）" value={formatYen(latest.totalDeposits)}
                delta={prev ? Number(latest.totalDeposits) - Number(prev.totalDeposits) : undefined} />
              <Kpi label="貸出金残高" value={formatYen(latest.totalLoans)}
                delta={prev ? Number(latest.totalLoans) - Number(prev.totalLoans) : undefined} />
              <Kpi label="預貸率" value={formatPercent(ratio(latest.totalLoans, latest.totalDeposits))} />
              <Kpi label="不良債権比率" value={formatPercent(ratio(latest.nplAmount, latest.totalLoans), 2)}
                sub={formatYen(latest.nplAmount)} />
              <Kpi label="顧客数" value={`${formatNumber(latest.customerCount)} 先`}
                sub={`口座 ${formatNumber(latest.accountCount)}（利用可 ${formatNumber(latest.activeAccounts)}）`} />
              <Kpi label="当日の取引" value={`${formatNumber(latest.txCount)} 件`}
                sub={`新規口座 ${latest.newAccounts}・解約 ${latest.closedAccounts}`} />
            </dl>
            <p className="section-note" style={{ margin: 0 }}>前日比は直前の取込日との比較です。</p>
          </section>

          <section className="card">
            <TrendChart title="預金・貸出金残高の推移" dates={series.map((r) => r.snapshotDate)}
              series={[
                { label: "預金", values: series.map((r) => Number(r.totalDeposits)) },
                { label: "貸出金", values: series.map((r) => Number(r.totalLoans)) },
              ]} />
          </section>

          <section className="card">
            <h2>期間損益（概算）</h2>
            <p className="section-note">{view.from} 〜 {view.to} の円口座の取引から集計。人件費・物件費・税は含みません。</p>
            <dl className="stats">
              <Kpi label="貸出金利息（延滞金・当座貸越利息を含む）" value={formatYen(view.profit.loanInterestIncome)} />
              <Kpi label="手数料収入" value={formatYen(view.profit.feeIncome)} />
              <Kpi label="預金利息（支払）" value={formatYen(view.profit.depositInterestPaid)} />
              <Kpi label="差引" value={formatYen(view.profit.net)} tone={Number(view.profit.net) < 0 ? "minus" : undefined} />
            </dl>
          </section>

          {/* 残高の構成：預金種類別・商品別・債務者区分別・顧客セグメント別 */}
          <section className="card">
            <h2>残高の構成（基準日）</h2>
            <div className="grid-2">
              <Bars title="預金種類別（円換算）" rows={view.depositMix} value="balanceJpy"
                label={(r) => `${accountTypeLabel[r.accountType as keyof typeof accountTypeLabel] ?? r.accountType}${r.currency === "JPY" ? "" : ` ${r.currency}`}`}
                sub={(r) => `${r.accounts} 口座`} />
              <Bars title="貸出 商品別" rows={view.loanMix} value="outstanding" label={(r) => productLabel[r.product] ?? r.product}
                sub={(r) => `${r.loans} 件`} />
              <Bars title="貸出 債務者区分別" rows={view.loanClassMix} value="outstanding"
                label={(r) => borrowerClassLabel[r.borrowerClass] ?? r.borrowerClass} sub={(r) => `${r.loans} 件`} />
              <Bars title="顧客セグメント別 預かり資産" rows={view.segments} value="totalAssets"
                label={(r) => segmentLabel[r.segment] ?? r.segment} sub={(r) => `${r.customers} 先`} />
            </div>
          </section>

          {/* 月次サマリー（月末時点の残高と月間の件数・収益） */}
          <section className="card">
            <h2>月次サマリー</h2>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>月</th><th className="num">月末 預金</th><th className="num">月末 貸出金</th><th className="num">顧客数</th>
                    <th className="num">取引件数</th><th className="num">新規口座</th><th className="num">解約</th>
                    <th className="num">貸出金利息</th><th className="num">手数料</th><th className="num">預金利息</th>
                  </tr>
                </thead>
                <tbody>
                  {(view.monthly as Row[]).map((m) => (
                    <tr key={m.month}>
                      <td>{m.month}<span className="muted">（{m.days}日）</span></td>
                      <td className="num">{formatYen(m.totalDeposits)}</td>
                      <td className="num">{formatYen(m.totalLoans)}</td>
                      <td className="num">{formatNumber(m.customerCount)}</td>
                      <td className="num">{formatNumber(m.txCount)}</td>
                      <td className="num">{formatNumber(m.newAccounts)}</td>
                      <td className="num">{formatNumber(m.closedAccounts)}</td>
                      <td className="num">{formatYen(m.loanInterestIncome)}</td>
                      <td className="num">{formatYen(m.feeIncome)}</td>
                      <td className="num">{formatYen(m.depositInterestPaid)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}

      <DwLoad status={view.status} />
    </>
  );
}

/** a ÷ b。分母が 0 以下なら 0 を返す（0 除算を避ける）。 */
function ratio(a: unknown, b: unknown): number {
  const d = Number(b);
  return d > 0 ? Number(a) / d : 0;
}

/** DWH の取込状況と、期間を指定した取込み（バックフィル・再取込）。 */
function DwLoad({ status }: { status: Row }) {
  // 取込は前日分まで（当日はまだ取引が確定していないため）。既定は直近 30 日。
  const yesterday = addDays(todayIso(), -1);
  const [from, setFrom] = useState(addDays(yesterday, -29));
  const [to, setTo] = useState(yesterday);
  const [running, setRunning] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const run = async (e: React.FormEvent) => {
    e.preventDefault();
    if (running) return;
    setRunning(true);
    setErr(null);
    setDone(null);
    try {
      const r = await infoApi.load(from, to);
      setDone(`${r.length} 日分を取り込みました（${from} 〜 ${to}）。`);
      // 取込後は情報系の画面（MIS・顧客分析・帳票）のキャッシュをすべて無効化する
      invalidate("mis:*", "analytics:*", "report:*");
    } catch (x) {
      setErr(x);
    } finally {
      setRunning(false);
    }
  };

  return (
    <section className="card">
      <h2>情報系データの取込（DWH）</h2>
      <p className="section-note">
        毎晩の夜間バッチ（情報系取込）が前日分を自動で取り込みます。過去日の取込み・やり直しはここから
        （1 回 92 日まで、前日まで。同じ日は入れ替え）。
      </p>
      <dl className="stats">
        <Kpi label="取込済み期間" value={status.firstDate ? `${status.firstDate} 〜 ${status.lastDate}` : "未取込"}
          sub={status.days ? `${status.days} 日分` : undefined} />
        <Kpi label="最終取込" value={status.lastLoad ? formatDateTime(status.lastLoad.loadedAt) : "—"}
          sub={status.lastLoad ? `${status.lastLoad.snapshotDate} 分・${status.lastLoad.loadedBy}` : undefined} />
      </dl>
      <ErrorBox error={err} />
      {done && <SuccessBox>{done}</SuccessBox>}
      <form className="filters" onSubmit={run} style={{ marginBottom: 0 }}>
        <label>開始日<input type="date" value={from} max={yesterday} onChange={(e) => setFrom(e.target.value)} required /></label>
        <label>終了日<input type="date" value={to} max={yesterday} onChange={(e) => setTo(e.target.value)} required /></label>
        <button className="btn btn-secondary" type="submit" disabled={running}>{running ? "取込中…" : "取り込む"}</button>
      </form>
    </section>
  );
}

/** 指標 1 つ（値・前日比・補足）。前日比は増減で色を変える。 */
function Kpi({ label, value, sub, delta, tone }: { label: string; value: string; sub?: string; delta?: number; tone?: "minus" }) {
  return (
    <div className="stat">
      <dt>{label}</dt>
      <dd className={tone} style={{ fontSize: "1.2rem" }}>{value}</dd>
      {delta !== undefined && (
        <dd className={delta > 0 ? "delta-plus" : delta < 0 ? "delta-minus" : "muted"} style={{ fontSize: "0.78rem" }}>
          前日比 {delta > 0 ? "+" : delta < 0 ? "−" : "±"}{formatYen(Math.abs(delta))}
        </dd>
      )}
      {sub && <dd className="muted" style={{ fontWeight: 500, fontSize: "0.78rem" }}>{sub}</dd>}
    </div>
  );
}

/** Single-series horizontal bars (one hue, direct value labels, hover detail). */
// 内訳を横棒グラフで表示する（最大値を 100% とした相対的な長さ）
function Bars({ title, rows, value, label, sub }: {
  title: string; rows: Row[]; value: string; label: (r: Row) => string; sub: (r: Row) => string;
}) {
  const max = Math.max(1, ...rows.map((r) => Number(r[value])));
  return (
    <div>
      <h3>{title}</h3>
      {rows.length === 0 ? <p className="muted">該当なし</p> : (
        <div className="bars" role="list">
          {rows.map((r, i) => {
            const v = Number(r[value]);
            return (
              <div key={i} className="bar-row" role="listitem" title={`${label(r)}: ${formatYen(v)}（${sub(r)}）`}>
                <span className="bar-label">{label(r)}</span>
                <span className="bar-track"><span className="bar-fill" style={{ width: `${(v / max) * 100}%` }} /></span>
                <span className="bar-value">{formatYen(v)}</span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

"use client";

import { RequireRole } from "@/components/AuthProvider";
import { ErrorBox, Loading } from "@/components/Feedback";
import { useRiskDashboard, type Row } from "@/lib/banking";
import { accountTypeLabel, borrowerClassLabel, formatNumber, formatPercent, formatYen, productLabel } from "@/lib/format";

export default function RiskPage() {
  return (
    <RequireRole roles={["SUPERVISOR"]}>
      <RiskDashboard />
    </RequireRole>
  );
}

function RiskDashboard() {
  const { data, error, loading } = useRiskDashboard();
  if (!data) return error ? <ErrorBox error={error} /> : <Loading />;
  const credit = data.credit;
  const liq = data.liquidity;
  return (
    <>
      <div className="page-head">
        <div>
          <p className="eyebrow">Risk management</p>
          <h1>リスク管理</h1>
          <p>基準時点 {String(data.asOf).replace("T", " ")}{loading ? "（更新中…）" : ""}</p>
        </div>
      </div>
      <ErrorBox error={error} />

      <section className="card">
        <h2>信用リスク</h2>
        <dl className="stats">
          <Kpi label="貸出残高" value={formatYen(credit.totalOutstanding)} sub={`${credit.loanCount} 件`} />
          <Kpi label="与信額 EAD（未使用枠×50%を含む）" value={formatYen(credit.totalEad)} />
          <Kpi label="期待損失 EL（PD×LGD×EAD）" value={formatYen(credit.expectedLoss)}
            sub={Number(credit.totalEad) > 0 ? `EAD 比 ${formatPercent(Number(credit.expectedLoss) / Number(credit.totalEad), 2)}` : undefined} />
          <Kpi label="不良債権（要管理先以下）" value={formatYen(credit.nplAmount)} sub={`不良債権比率 ${formatPercent(Number(credit.nplRatio), 2)}`}
            tone={Number(credit.nplRatio) > 0.03 ? "minus" : undefined} />
          <Kpi label="貸倒引当金" value={formatYen(credit.totalProvision ?? 0)}
            sub={`貸出残高比 ${formatPercent(Number(credit.provisionToLoans ?? 0), 2)}`} />
          <Kpi label="不良債権の引当率（引当 ÷ 不良債権）" value={Number(credit.nplAmount) > 0 ? formatPercent(Number(credit.provisionToNpl)) : "—"}
            sub="保全額（担保）でカバーされる部分は引当不要" />
        </dl>
        <div className="form-grid" style={{ gap: 24 }}>
          <Bars title="格付別 EAD" rows={credit.byGrade} label={(r) => `格付 ${r.key}`} value="ead" />
          <Bars title="債務者区分別 残高" rows={credit.byClass} label={(r) => borrowerClassLabel[r.key]} value="outstanding" />
          <Bars title="商品別 EAD" rows={credit.byProduct} label={(r) => productLabel[r.key]} value="ead" />
        </div>
        {credit.topBorrowers.length > 0 && (
          <div className="table-wrap" style={{ marginTop: 16 }}>
            <h3>与信集中（上位5先）</h3>
            <table>
              <thead><tr><th>お客さま</th><th className="num">EAD</th><th className="num">シェア</th></tr></thead>
              <tbody>
                {credit.topBorrowers.map((b: Row) => (
                  <tr key={b.customerId}><td>{b.customerName}<span className="muted"> {b.customerId}</span></td>
                    <td className="num">{formatYen(b.ead)}</td><td className="num">{formatPercent(Number(b.share))}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card">
        <h2>流動性リスク</h2>
        <dl className="stats">
          <Kpi label="預金残高（外貨は仲値換算）" value={formatYen(liq.totalDeposits)} />
          <Kpi label="うち流動性預金" value={formatYen(liq.demandDeposits)} />
          <Kpi label="貸出金" value={formatYen(liq.totalLoans)} />
          <Kpi label="預貸率" value={formatPercent(Number(liq.loanToDepositRatio))} />
          <Kpi label="30日以内に満期の定期預金" value={formatYen(liq.timeDepositsMaturing30d.amount)}
            sub={`${liq.timeDepositsMaturing30d.n} 件`} />
        </dl>
        <div className="table-wrap">
          <table>
            <thead><tr><th>預金種類</th><th>通貨</th><th className="num">口座数</th><th className="num">残高（円換算）</th></tr></thead>
            <tbody>
              {liq.byType.map((r: Row) => (
                <tr key={`${r.accountType}-${r.currency}`}>
                  <td>{accountTypeLabel[r.accountType as keyof typeof accountTypeLabel] ?? r.accountType}</td>
                  <td>{r.currency}</td><td className="num">{formatNumber(r.accounts)}</td><td className="num">{formatYen(r.yenEquivalent)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card">
        <h2>市場リスク（為替）</h2>
        <p className="section-note">お客さまの外貨預金は当行にとって外貨建ての負債（為替ヘッジなしの売り持ち）。円安 1% で円換算の負債が増える額を表示します。</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>通貨</th><th className="num">ポジション</th><th className="num">仲値</th><th className="num">円換算</th><th className="num">円安1%の影響</th></tr></thead>
            <tbody>
              {data.fx.positions.map((p: Row) => (
                <tr key={p.currency}>
                  <td><strong>{p.currency}</strong></td>
                  <td className="num">{Number(p.position).toLocaleString("ja-JP", { minimumFractionDigits: 2 })}</td>
                  <td className="num">{Number(p.ttm).toFixed(2)}</td>
                  <td className="num">{formatYen(p.yenEquivalent)}</td>
                  <td className="num minus">{formatYen(p.pnlIfYenWeakens1pct)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}

function Kpi({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: "minus" }) {
  return (
    <div className="stat">
      <dt>{label}</dt>
      <dd className={tone} style={{ fontSize: "1.25rem" }}>{value}</dd>
      {sub && <dd className="muted" style={{ fontWeight: 500, fontSize: "0.78rem" }}>{sub}</dd>}
    </div>
  );
}

/** Single-series horizontal bars (one hue, direct value labels, hover detail). */
function Bars({ title, rows, label, value }: { title: string; rows: Row[]; label: (r: Row) => string; value: "ead" | "outstanding" }) {
  const max = Math.max(1, ...rows.map((r) => Number(r[value])));
  return (
    <div>
      <h3>{title}</h3>
      <div className="bars" role="list">
        {rows.map((r) => {
          const v = Number(r[value]);
          return (
            <div key={r.key} className="bar-row" role="listitem"
              title={`${label(r)}: ${formatYen(v)}（${r.count} 件・EL ${formatYen(r.expectedLoss)}）`}>
              <span className="bar-label">{label(r)}</span>
              <span className="bar-track">
                <span className="bar-fill" style={{ width: `${(v / max) * 100}%` }} />
              </span>
              <span className="bar-value">{v > 0 ? formatYen(v) : "—"}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

"use client";

import Link from "next/link";
import { useState } from "react";
import { RequireRole } from "@/components/AuthProvider";
import { ErrorBox, Loading } from "@/components/Feedback";
import { useCustomerAnalytics, type Row } from "@/lib/banking";
import { downloadCsv } from "@/lib/csv";
import { customerTypeLabel, formatNumber, formatYen, segmentLabel } from "@/lib/format";

/** 情報系：顧客分析（RFM セグメント・クロスセル候補・離反リスク）。営業担当（窓口・役席）向け。 */
export default function AnalyticsPage() {
  return (
    <RequireRole roles={["TELLER", "SUPERVISOR"]}>
      <CustomerAnalytics />
    </RequireRole>
  );
}

type Tab = "crossSell" | "churn" | "all";

function CustomerAnalytics() {
  const [date, setDate] = useState<string>("");
  const { data, error, loading } = useCustomerAnalytics(date || undefined);
  const [tab, setTab] = useState<Tab>("crossSell");
  const [segment, setSegment] = useState<string>("");

  if (!data) return error ? <ErrorBox error={error} /> : <Loading />;
  if (!data.date) {
    return (
      <>
        <Head shown={null} loading={loading} date={date} onDate={setDate} />
        <ErrorBox error={error} />
        <section className="card">
          <p>情報系のデータがまだ取り込まれていません。役席が「経営ダッシュボード」の「情報系データの取込」から取り込むか、
            夜間バッチ（毎日 01:00 以降）をお待ちください。</p>
        </section>
      </>
    );
  }
  const customers: Row[] = data.customers;
  const segments: Row[] = data.segments;
  const total = segments.reduce((s, r) => s + Number(r.customers), 0);
  const filtered = segment ? customers.filter((c) => c.segment === segment) : customers;

  return (
    <>
      <Head shown={data.date} loading={loading} date={date} onDate={setDate} />
      <ErrorBox error={error} />

      <section className="card">
        <h2>セグメント</h2>
        <p className="section-note">
          R（最終取引からの日数）と M（預かり資産）で分類。優良＝R4以上・M4以上、成長＝R4以上、休眠リスク＝R2以下・M3以上、離反＝R2以下。
        </p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>セグメント</th><th className="num">顧客数</th><th className="num">構成比</th>
              <th className="num">預かり資産</th><th className="num">平均</th><th className="num">借入残高</th><th /></tr></thead>
            <tbody>
              {segments.map((s) => (
                <tr key={s.segment}>
                  <td><span className={`seg seg-${s.segment}`}>{segmentLabel[s.segment] ?? s.segment}</span></td>
                  <td className="num">{formatNumber(s.customers)}</td>
                  <td className="num">{total ? `${((Number(s.customers) / total) * 100).toFixed(1)}%` : "—"}</td>
                  <td className="num">{formatYen(s.totalAssets)}</td>
                  <td className="num">{formatYen(Math.round(Number(s.totalAssets) / Math.max(1, Number(s.customers))))}</td>
                  <td className="num">{formatYen(s.totalLoans)}</td>
                  <td><button type="button" className="link-button" onClick={() => { setSegment(s.segment); setTab("all"); }}>
                    一覧</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="grid-2" style={{ marginTop: 16 }}>
          <Distribution title="R スコア（最近の取引）" rows={data.rDistribution} total={total} />
          <Distribution title="M スコア（預かり資産）" rows={data.mDistribution} total={total} />
        </div>
      </section>

      <section className="card">
        <div className="tabs" role="tablist">
          <button type="button" role="tab" className="tab" aria-selected={tab === "crossSell"} onClick={() => setTab("crossSell")}>
            クロスセル候補 {data.crossSell.length}</button>
          <button type="button" role="tab" className="tab" aria-selected={tab === "churn"} onClick={() => setTab("churn")}>
            離反リスク {data.churnRisk.length}</button>
          <button type="button" role="tab" className="tab" aria-selected={tab === "all"} onClick={() => setTab("all")}>
            顧客一覧 {customers.length}</button>
        </div>
        {tab === "crossSell" && <CrossSell rows={data.crossSell} date={data.date} />}
        {tab === "churn" && <Churn rows={data.churnRisk} date={data.date} />}
        {tab === "all" && (
          <AllCustomers rows={filtered} date={data.date} segment={segment} onSegment={setSegment} />
        )}
      </section>
    </>
  );

}

function Head({ shown, loading, date, onDate }: {
  shown: string | null; loading: boolean; date: string; onDate: (d: string) => void;
}) {
  return (
    <div className="page-head">
      <div>
        <p className="eyebrow">Information systems · Customer analytics</p>
        <h1>顧客分析</h1>
        <p>{shown ? `基準日 ${shown}（その日までの取引で集計）` : "データなし"}{loading ? "（更新中…）" : ""}</p>
      </div>
      <label style={{ minWidth: 180 }}>基準日
        <input type="date" value={date} onChange={(e) => onDate(e.target.value)} aria-describedby="date-hint" />
        <span id="date-hint" className="hint">空欄は最新の取込日</span>
      </label>
    </div>
  );
}

function Distribution({ title, rows, total }: { title: string; rows: Row[]; total: number }) {
  const max = Math.max(1, ...rows.map((r) => Number(r.customers)));
  return (
    <div>
      <h3>{title}</h3>
      <div className="bars" role="list">
        {[5, 4, 3, 2, 1].map((score) => {
          const n = Number(rows.find((r) => Number(r.score) === score)?.customers ?? 0);
          return (
            <div key={score} className="bar-row" role="listitem" title={`スコア ${score}: ${n} 先`}>
              <span className="bar-label">スコア {score}</span>
              <span className="bar-track"><span className="bar-fill" style={{ width: `${(n / max) * 100}%` }} /></span>
              <span className="bar-value">{n} 先{total ? `（${Math.round((n / total) * 100)}%）` : ""}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function CustomerLink({ r }: { r: Row }) {
  return (
    <Link href={`/customers/${r.customerId}`}>
      {r.name}<span className="muted"> {r.customerId}</span>
    </Link>
  );
}

function CrossSell({ rows, date }: { rows: Row[]; date: string }) {
  return (
    <>
      <div className="actions" style={{ justifyContent: "space-between", marginBottom: 8 }}>
        <p className="section-note" style={{ margin: 0 }}>保有していない商品のうち、資産規模・属性から提案しやすいもの（預かり資産の多い順）。</p>
        <button type="button" className="btn btn-secondary btn-small" disabled={rows.length === 0}
          onClick={() => downloadCsv(`cross_sell_${date}.csv`, ["顧客番号", "氏名", "セグメント", "預かり資産", "提案商品", "理由"],
            rows.map((r) => [r.customerId, r.name, segmentLabel[r.segment] ?? r.segment, r.totalAssets, r.productLabel, r.reason]))}>
          CSV出力</button>
      </div>
      {rows.length === 0 ? <p className="muted">候補はありません。</p> : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>お客さま</th><th>セグメント</th><th className="num">預かり資産</th><th>提案商品</th><th>理由</th></tr></thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={`${r.customerId}-${r.product}-${i}`}>
                  <td><CustomerLink r={r} /></td>
                  <td><span className={`seg seg-${r.segment}`}>{segmentLabel[r.segment] ?? r.segment}</span></td>
                  <td className="num">{formatYen(r.totalAssets)}</td>
                  <td><strong>{r.productLabel}</strong></td>
                  <td className="muted" style={{ whiteSpace: "normal" }}>{r.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function Churn({ rows, date }: { rows: Row[]; date: string }) {
  return (
    <>
      <div className="actions" style={{ justifyContent: "space-between", marginBottom: 8 }}>
        <p className="section-note" style={{ margin: 0 }}>資産はあるが取引が途絶えている、または 30 日前より預かり資産が 30% 以上減ったお客さま。</p>
        <button type="button" className="btn btn-secondary btn-small" disabled={rows.length === 0}
          onClick={() => downloadCsv(`churn_risk_${date}.csv`, ["顧客番号", "氏名", "セグメント", "預かり資産", "30日前", "最終取引日", "理由"],
            rows.map((r) => [r.customerId, r.name, segmentLabel[r.segment] ?? r.segment, r.totalAssets, r.assetsBefore ?? "",
              r.lastActivity ?? "", r.reason]))}>
          CSV出力</button>
      </div>
      {rows.length === 0 ? <p className="muted">該当するお客さまはいません。</p> : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>お客さま</th><th>セグメント</th><th className="num">預かり資産</th><th className="num">30日前</th>
              <th>最終取引日</th><th>理由</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.customerId}>
                  <td><CustomerLink r={r} /></td>
                  <td><span className={`seg seg-${r.segment}`}>{segmentLabel[r.segment] ?? r.segment}</span></td>
                  <td className="num">{formatYen(r.totalAssets)}</td>
                  <td className="num">{r.assetsBefore != null ? formatYen(r.assetsBefore) : "—"}</td>
                  <td>{r.lastActivity ?? "—"}</td>
                  <td className="muted" style={{ whiteSpace: "normal" }}>{r.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function AllCustomers({ rows, date, segment, onSegment }: {
  rows: Row[]; date: string; segment: string; onSegment: (s: string) => void;
}) {
  return (
    <>
      <div className="actions" style={{ justifyContent: "space-between", marginBottom: 8 }}>
        <label style={{ minWidth: 180 }}>セグメント
          <select value={segment} onChange={(e) => onSegment(e.target.value)}>
            <option value="">すべて</option>
            {Object.entries(segmentLabel).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
        <button type="button" className="btn btn-secondary btn-small" disabled={rows.length === 0}
          onClick={() => downloadCsv(`customers_rfm_${date}${segment ? `_${segment}` : ""}.csv`,
            ["顧客番号", "氏名", "区分", "セグメント", "預かり資産", "借入残高", "保有商品数", "最終取引日", "経過日数", "取引回数90日", "R", "F", "M"],
            rows.map((r) => [r.customerId, r.name, customerTypeLabel[r.customerType] ?? r.customerType, segmentLabel[r.segment] ?? r.segment,
              r.totalAssets, r.totalLoans, r.productCount, r.lastActivity ?? "", r.recencyDays ?? "", r.frequency90d,
              r.rScore, r.fScore, r.mScore]))}>
          CSV出力</button>
      </div>
      <div className="table-wrap">
        <table>
          <thead><tr><th>お客さま</th><th>区分</th><th>セグメント</th><th className="num">預かり資産</th><th className="num">借入</th>
            <th className="num">商品数</th><th>最終取引日</th><th className="num">90日取引</th><th className="num">R / F / M</th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.customerId}>
                <td><CustomerLink r={r} /></td>
                <td>{customerTypeLabel[r.customerType] ?? r.customerType}</td>
                <td><span className={`seg seg-${r.segment}`}>{segmentLabel[r.segment] ?? r.segment}</span></td>
                <td className="num">{formatYen(r.totalAssets)}</td>
                <td className="num">{formatYen(r.totalLoans)}</td>
                <td className="num">{r.productCount}</td>
                <td>{r.lastActivity ?? "—"}{r.recencyDays != null && <span className="muted">（{r.recencyDays}日前）</span>}</td>
                <td className="num">{r.frequency90d}</td>
                <td className="num">{r.rScore} / {r.fScore} / {r.mScore}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

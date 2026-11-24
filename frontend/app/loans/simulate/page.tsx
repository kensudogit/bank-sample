"use client";

// 返済シミュレーションの画面（/loans/simulate）。借入条件から返済予定表を作り、元利均等と元金均等を比較する。

import Link from "next/link";
import { useState } from "react";
import { ErrorBox } from "@/components/Feedback";
import { IconArrowLeft } from "@/components/Icons";
import { loanApi, type Row } from "@/lib/banking";
import { downloadCsv } from "@/lib/csv";
import { formatDate, formatYen, methodLabel, normalizeAmount } from "@/lib/format";

/** 返済シミュレーション：借入額・金利・回数・返済方法から返済予定表を作り、元利均等と元金均等を比べる（ログイン中の全員）。 */
export default function SimulatePage() {
  const [amount, setAmount] = useState("30000000");
  const [rate, setRate] = useState("1.5");
  const [years, setYears] = useState("35");
  const [method, setMethod] = useState("EQUAL_PAYMENT");
  const [paymentDay, setPaymentDay] = useState("27");
  const [result, setResult] = useState<Row | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [showAll, setShowAll] = useState(false);

  // 計算はサーバー側（融資の本番計算と同じロジック）で行う。m を渡すと、その返済方法で再計算する。
  const run = async (e?: React.FormEvent, m?: string) => {
    e?.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      const months = String(Math.round(Number(years) * 12));
      setResult(await loanApi.simulate({ amount: normalizeAmount(amount), rate, termMonths: months, repaymentMethod: m ?? method,
        paymentDay: paymentDay || undefined }));
      setShowAll(false);
    } catch (x) {
      setErr(x);
    } finally {
      setBusy(false);
    }
  };

  const rows: Row[] = result?.schedule ?? [];
  // 返済予定表は長くなる（最大 420 回）ため、既定では最初の 12 回だけ表示する
  const visible = showAll ? rows : rows.slice(0, 12);

  return (
    <>
      <Link href="/loans" className="back-link"><IconArrowLeft size={16} /> 融資</Link>
      <div className="page-head">
        <div>
          <p className="eyebrow">Loan simulation</p>
          <h1>返済シミュレーション</h1>
          <p>月利息 = 残高 × 年利 ÷ 12（円未満切捨て）。返済日が休業日なら翌営業日。実際の金利は審査結果（格付）で決まります。</p>
        </div>
      </div>

      <section className="card">
        <form className="filters" onSubmit={(e) => run(e)} style={{ marginBottom: 0 }}>
          <label>借入額（円）<input inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)} required /></label>
          <label>年利（%）<input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} required /></label>
          <label>返済期間（年）<input type="number" min={1} max={35} step={0.5} value={years} onChange={(e) => setYears(e.target.value)} required /></label>
          <label>返済方法
            <select value={method} onChange={(e) => setMethod(e.target.value)}>
              <option value="EQUAL_PAYMENT">元利均等（毎回の返済額が一定）</option>
              <option value="EQUAL_PRINCIPAL">元金均等（元金が一定・返済額は逓減）</option>
            </select>
          </label>
          <label>毎月の返済日<input type="number" min={1} max={28} value={paymentDay} onChange={(e) => setPaymentDay(e.target.value)} /></label>
          <button className="btn" type="submit" disabled={busy}>{busy ? "計算中…" : "計算する"}</button>
        </form>
        <ErrorBox error={err} />
      </section>

      {result && (
        <>
          <section className="card">
            <h2>比較（{formatYen(result.principal)}・年 {Number(result.annualRate)}%・{result.months} 回）</h2>
            <div className="table-wrap">
              <table>
                <thead><tr><th>返済方法</th><th className="num">初回</th><th className="num">最終回</th><th className="num">最大</th>
                  <th className="num">利息の総額</th><th className="num">総返済額</th><th /></tr></thead>
                <tbody>
                  {(result.comparison as Row[]).map((c) => (
                    <tr key={c.method} style={c.method === result.method ? { fontWeight: 700 } : undefined}>
                      <td>{methodLabel[c.method] ?? c.method}{c.method === result.method && <span className="muted">（表示中）</span>}</td>
                      <td className="num">{formatYen(c.firstPayment)}</td>
                      <td className="num">{formatYen(c.lastPayment)}</td>
                      <td className="num">{formatYen(c.maxPayment)}</td>
                      <td className="num">{formatYen(c.totalInterest)}</td>
                      <td className="num">{formatYen(c.totalPayment)}</td>
                      <td>{c.method !== result.method && (
                        <button type="button" className="link-button" onClick={() => { setMethod(c.method); run(undefined, c.method); }}>
                          この方法で表示</button>)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="section-note" style={{ margin: "8px 0 0" }}>
              元金均等は早く元金が減るため利息の総額は少なくなりますが、返済開始時の負担が大きくなります。
            </p>
          </section>

          {/* 返済予定表。CSV 出力は表示件数に関係なく全回分を出す */}
          <section className="card table-wrap">
            <div className="chart-head">
              <h2 style={{ margin: 0 }}>返済予定表（{methodLabel[result.method]}・毎月 {result.paymentDay} 日）</h2>
              <button type="button" className="btn btn-secondary btn-small"
                onClick={() => downloadCsv(`loan_simulation_${result.principal}_${result.months}.csv`,
                  ["回", "返済日", "元金", "利息", "返済額", "返済後残高"],
                  rows.map((r) => [r.no, r.dueDate, r.principal, r.interest, r.total, r.balanceAfter]))}>
                CSV出力</button>
            </div>
            <table>
              <thead><tr><th>回</th><th>返済日</th><th className="num">元金</th><th className="num">利息</th><th className="num">返済額</th>
                <th className="num">返済後残高</th></tr></thead>
              <tbody>
                {visible.map((r) => (
                  <tr key={r.no}>
                    <td>{r.no}</td><td>{formatDate(r.dueDate)}</td>
                    <td className="num">{formatYen(r.principal)}</td><td className="num">{formatYen(r.interest)}</td>
                    <td className="num">{formatYen(r.total)}</td><td className="num">{formatYen(r.balanceAfter)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {rows.length > 12 && (
              <div className="pager">
                <span className="muted">{showAll ? `全 ${rows.length} 回` : `最初の 12 回（全 ${rows.length} 回）`}</span>
                <button className="btn btn-secondary btn-small" type="button" onClick={() => setShowAll(!showAll)}>
                  {showAll ? "最初の 12 回のみ" : "すべて表示"}</button>
              </div>
            )}
          </section>
        </>
      )}
    </>
  );
}

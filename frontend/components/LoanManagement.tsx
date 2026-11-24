"use client";

// 融資詳細画面に載せる行員向けの「融資管理」パネル群。
// 貸倒引当金の計上状況と履歴、担保の保全額と再評価、条件変更（リスケジュール）の履歴と実行フォームを表示する。

import { useState } from "react";
import { ErrorBox } from "@/components/Feedback";
import { loanApi, useLoanManagement, type Row } from "@/lib/banking";
import { borrowerClassLabel, formatDate, formatDateTime, formatPercent, formatYen, normalizeAmount } from "@/lib/format";

/** 担保・保証の種類 → 日本語表示名 */
const collateralLabel: Record<string, string> = {
  REAL_ESTATE: "不動産", DEPOSIT: "預金", SECURITIES: "有価証券", GUARANTOR: "個人保証", GUARANTEE_CO: "保証会社",
};

/** 親画面から受け取る実行ヘルパー。API を呼んで結果メッセージを表示し、成功したら true を返す。 */
type Run = (fn: () => Promise<any>, done: string) => Promise<boolean | void>;

/**
 * 融資の管理（行員向け）：貸倒引当金・担保の保全額と再評価・条件変更。
 * 引当と条件変更の内容はお客さまには表示しない（API も行員のみ）。
 */
export function LoanManagement({ loanId, loan, supervisor, run }: { loanId: string; loan: Row; supervisor: boolean; run: Run }) {
  const { data: m, error } = useLoanManagement(loanId);
  if (!m) return error ? <ErrorBox error={error} /> : null;
  // 返済中・延滞中の融資だけが再評価・条件変更の対象（完済・償却済みは閲覧のみ）
  const live = loan.status === "ACTIVE" || loan.status === "DELINQUENT";
  const provisions: Row[] = m.provisions;
  const collaterals: Row[] = m.collaterals;

  return (
    <>
      <section className="card">
        <h2>貸倒引当金{m.restructured ? <span className="seg seg-AT_RISK" style={{ marginLeft: 8 }}>条件変更あり（{m.restructureCount} 回）</span> : null}</h2>
        <p className="section-note">
          正常先 0.5%・要注意先 3%・要管理先 15% は残高に、破綻懸念先 70%・実質破綻先／破綻先 100% は非保全額（残高 − 保全額）に掛けます。
          毎晩の約定返済処理の後に見直します。
        </p>
        <dl className="stats">
          <Stat label="計上済みの引当" value={formatYen(m.provisionAmount)}
            sub={m.provisionUpdatedAt ? `更新 ${formatDateTime(m.provisionUpdatedAt)}` : "未計上"} />
          <Stat label="現時点の必要額" value={formatYen(m.requiredProvision)}
            sub={Number(m.requiredProvision) !== Number(m.provisionAmount) ? "次回の見直しで調整" : "計上済みと一致"} />
          <Stat label="債務者区分" value={borrowerClassLabel[m.borrowerClass] ?? m.borrowerClass}
            sub={m.unsecuredBasis ? "非保全額に引当" : "残高に引当"} />
          <Stat label="保全額（担保 × 掛目）" value={formatYen(m.secured)} sub={`非保全 ${formatYen(m.unsecured)}`} />
        </dl>
        {provisions.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>基準日</th><th>区分</th><th className="num">残高</th><th className="num">保全額</th><th className="num">引当率</th>
                <th className="num">必要額</th><th className="num">増減</th><th>理由</th></tr></thead>
              <tbody>
                {provisions.map((p) => (
                  <tr key={p.provisionId}>
                    <td>{formatDate(p.asOf)}</td>
                    <td>{borrowerClassLabel[p.borrowerClass] ?? p.borrowerClass}</td>
                    <td className="num">{formatYen(p.outstanding)}</td>
                    <td className="num">{formatYen(p.secured)}</td>
                    <td className="num">{formatPercent(Number(p.provisionRate))}</td>
                    <td className="num">{formatYen(p.required)}</td>
                    <td className={`num ${Number(p.changeAmount) > 0 ? "minus" : "plus"}`}>
                      {Number(p.changeAmount) > 0 ? "+" : "−"}{formatYen(Math.abs(Number(p.changeAmount)))}</td>
                    <td>{p.reason}<span className="muted">（{p.createdBy}）</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* 担保ごとの評価額・掛目・保全額。役席は再評価できる */}
      {collaterals.length > 0 && (
        <section className="card">
          <h2>担保・保証の保全額</h2>
          <div className="table-wrap">
            <table>
              <thead><tr><th>種類</th><th>内容</th><th className="num">評価額</th><th className="num">掛目</th><th className="num">保全額</th>
                <th>最終評価</th>{supervisor && live && <th>再評価（役席）</th>}</tr></thead>
              <tbody>
                {collaterals.map((k) => (
                  <CollateralRow key={k.collateralId} k={k} canRevalue={supervisor && live} run={run} />
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {/* 条件変更の履歴（変更前 → 変更後） */}
      {(m.restructures as Row[]).length > 0 && (
        <section className="card table-wrap">
          <h2>条件変更の履歴</h2>
          <table>
            <thead><tr><th>実施日</th><th className="num">残高</th><th className="num">延滞利息</th><th className="num">金利</th>
              <th className="num">残り回数</th><th className="num">毎月の返済額</th><th>満期</th><th>理由</th></tr></thead>
            <tbody>
              {(m.restructures as Row[]).map((r) => (
                <tr key={r.restructureId}>
                  <td>{formatDateTime(r.createdAt)}</td>
                  <td className="num">{formatYen(r.outstanding)}</td>
                  <td className="num">{formatYen(r.arrearsInterest)}</td>
                  <td className="num">{Number(r.oldRate).toFixed(3)}% → {Number(r.newRate).toFixed(3)}%</td>
                  <td className="num">{r.oldRemaining} → {r.newRemaining}</td>
                  <td className="num">{r.oldPayment != null ? formatYen(r.oldPayment) : "—"} → {formatYen(r.newPayment)}</td>
                  <td>{formatDate(r.oldMaturity)} → {formatDate(r.newMaturity)}</td>
                  <td style={{ whiteSpace: "normal" }}>{r.reason}<span className="muted">（{r.approvedBy}・延滞 {r.daysPastDue} 日）</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      {/* リボルビング（カードローン等）には返済回数の概念がないため条件変更フォームを出さない */}
      {supervisor && live && loan.repaymentMethod !== "REVOLVING" && <RescheduleForm loan={loan} run={run} />}
    </>
  );
}

/** 担保 1 件の行。再評価の履歴はマウスを乗せると title で確認できる。再評価すると引当も見直される。 */
function CollateralRow({ k, canRevalue, run }: { k: Row; canRevalue: boolean; run: Run }) {
  const [value, setValue] = useState("");
  const [note, setNote] = useState("");
  const history: Row[] = k.revaluations;
  return (
    <tr>
      <td>{collateralLabel[k.collateralType] ?? k.collateralType}</td>
      <td style={{ whiteSpace: "normal" }}>{k.description}{k.guarantorName ? `（${k.guarantorName}）` : ""}</td>
      <td className="num">{formatYen(k.appraisalValue)}</td>
      <td className="num">{formatPercent(Number(k.haircut), 0)}</td>
      <td className="num">{formatYen(k.recognized)}</td>
      <td>
        {k.revaluedAt ? formatDate(k.revaluedAt) : formatDate(k.registeredAt)}
        {history.length > 0 && (
          <span className="muted" title={history.map((h) => `${formatDate(h.revaluedAt)} ${h.previousValue}→${h.newValue} ${h.reason}`).join("\n")}>
            （再評価 {history.length} 回）</span>
        )}
      </td>
      {canRevalue && (
        <td>
          <form className="actions" onSubmit={(e) => {
            e.preventDefault();
            run(() => loanApi.revalue({ collateralId: String(k.collateralId), appraisalValue: normalizeAmount(value), note }),
              "担保を再評価し、引当を見直しました").then((ok) => { if (ok) { setValue(""); setNote(""); } });
          }}>
            <input aria-label="新しい評価額" placeholder="新しい評価額" inputMode="numeric" value={value}
              onChange={(e) => setValue(e.target.value)} required style={{ width: 130 }} />
            <input aria-label="再評価の理由" placeholder="理由（鑑定・路線価など）" value={note} onChange={(e) => setNote(e.target.value)}
              required maxLength={200} style={{ width: 180 }} />
            <button className="btn btn-secondary btn-small" type="submit">再評価</button>
          </form>
        </td>
      )}
    </tr>
  );
}

/**
 * 条件変更フォーム（役席のみ）。残り回数の延長や金利の引き下げで返済予定表を作り直す。
 * 条件緩和した債権は要管理先以下の扱いになり引当に影響するため、実行前に確認ダイアログを出す。
 */
function RescheduleForm({ loan, run }: { loan: Row; run: Run }) {
  const remaining = (loan.schedule as Row[]).filter((s) => ["SCHEDULED", "PARTIAL", "OVERDUE"].includes(s.status)).length;
  // 初期値は「現在の残り回数 + 5 年」（上限 35 年 = 420 回）
  const [months, setMonths] = useState(String(Math.min(420, remaining + 60)));
  const [rate, setRate] = useState("");
  const [note, setNote] = useState("");
  return (
    <section className="card">
      <h2>条件変更（役席）</h2>
      <p className="section-note">
        残りの元金 {formatYen(loan.outstanding)} を新しい回数（と、より低い金利）で返済し直します。延滞中の未払利息は新しい 1 回目に上乗せし、
        延滞は解消します。条件を緩和した債権は<strong>要管理先以下</strong>として扱い、引当も見直します。現在の残り回数 {remaining} 回・金利 {Number(loan.annualRate).toFixed(3)}%。
      </p>
      <form className="filters" style={{ marginBottom: 0 }} onSubmit={async (e) => {
        e.preventDefault();
        if (!window.confirm(`残り ${months} 回${rate ? `・年 ${rate}%` : ""}に条件変更します。よろしいですか？`)) return;
        const ok = await run(() => loanApi.reschedule({ loanId: String(loan.loanId), termMonths: months, rate: rate || undefined, note }),
          "条件変更しました（返済予定表を作り直しました）");
        if (ok) window.scrollTo({ top: 0, behavior: "smooth" });   // 結果は画面上部に表示される
      }}>
        <label>新しい残り回数<input type="number" min={1} max={420} value={months} onChange={(e) => setMonths(e.target.value)} required /></label>
        <label>新しい年利（%・空欄は据え置き）<input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)}
          placeholder={Number(loan.annualRate).toFixed(3)} /></label>
        <label style={{ flex: "1 1 260px" }}>理由<input value={note} onChange={(e) => setNote(e.target.value)} required maxLength={200}
          placeholder="業況悪化による返済期間の延長 など" /></label>
        <button className="btn btn-danger" type="submit">条件変更する</button>
      </form>
    </section>
  );
}

/** 指標 1 つ（ラベル・値・補足）の表示 */
function Stat({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="stat">
      <dt>{label}</dt>
      <dd style={{ fontSize: "1.15rem" }}>{value}</dd>
      {sub && <dd className="muted" style={{ fontWeight: 500, fontSize: "0.78rem" }}>{sub}</dd>}
    </div>
  );
}

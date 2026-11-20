"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { IconArrowLeft } from "@/components/Icons";
import { ApiError, newIdempotencyKey } from "@/lib/api";
import { loanApi, useLoan, type Row } from "@/lib/banking";
import {
  borrowerClassLabel,
  formatDate,
  formatDateTime,
  formatYen,
  loanStatusLabel,
  methodLabel,
  normalizeAmount,
  productLabel,
} from "@/lib/format";

const scheduleLabel: Record<string, string> = { SCHEDULED: "予定", PARTIAL: "一部入金", PAID: "返済済", OVERDUE: "延滞", CANCELLED: "取消" };
const actionLabel: Record<string, string> = { CALL: "電話", LETTER: "督促状", VISIT: "訪問", PROMISE: "返済約束", LEGAL: "法的手続" };

export default function LoanPage() {
  const { id } = useParams<{ id: string }>();
  const { user } = useAuth();
  const { data: loan, error } = useLoan(id);
  const [err, setErr] = useState<unknown>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [showAll, setShowAll] = useState(false);

  const run = async (fn: () => Promise<any>, done: string) => {
    setErr(null);
    setMessage(null);
    try {
      await fn();
      setMessage(done);
    } catch (e) {
      setErr(e);
    }
  };

  if (!loan) return error ? <ErrorBox error={error} /> : <Loading />;
  const staff = user?.staff ?? false;
  const supervisor = user?.role === "SUPERVISOR";
  const live = loan.status === "ACTIVE" || loan.status === "DELINQUENT";
  const revolving = loan.repaymentMethod === "REVOLVING";
  const schedule: Row[] = loan.schedule;
  const visible = showAll ? schedule : schedule.filter((s) => s.status !== "PAID" && s.status !== "CANCELLED").slice(0, 12);
  const paidCount = schedule.filter((s) => s.status === "PAID").length;

  return (
    <>
      <Link href="/loans" className="back-link"><IconArrowLeft size={16} /> 融資</Link>
      <section className="hero">
        <div className="tile-top" style={{ position: "relative" }}>
          <span className="hero-label">{productLabel[loan.product]} ・ No.{loan.loanId}</span>
          <span className="chip">{loanStatusLabel[loan.status]}</span>
        </div>
        <div className="hero-amount">{formatYen(loan.outstanding)}</div>
        <div className="hero-meta">
          <span>お客さま <strong>{loan.customerName}</strong></span>
          {revolving ? <span>極度額 <strong>{formatYen(loan.creditLimit)}</strong></span> : <span>当初 <strong>{formatYen(loan.principal)}</strong></span>}
          <span>金利 <strong>{Number(loan.annualRate).toFixed(3)}%</strong></span>
          <span>返済 <strong>{methodLabel[loan.repaymentMethod]}・毎月{loan.paymentDay}日</strong></span>
          <span>返済口座 <strong>{loan.repaymentAccountNo}</strong></span>
          <span>債務者区分 <strong>{borrowerClassLabel[loan.borrowerClass]}</strong></span>
          {loan.daysPastDue > 0 && <span>延滞 <strong>{loan.daysPastDue}日</strong></span>}
          {loan.product === "CARD" && <span>未払利息 <strong>{formatYen(Math.floor(Number(loan.accruedInterest)))}</strong></span>}
        </div>
      </section>
      <ErrorBox error={error ?? err} />
      {message && <SuccessBox>{message}</SuccessBox>}

      {live && loan.product !== "OVERDRAFT" && <PaymentForms loan={loan} run={run} />}

      {!revolving && schedule.length > 0 && (
        <section className="card table-wrap">
          <div className="chart-head">
            <h2 style={{ margin: 0 }}>返済予定表</h2>
            <span className="muted">返済済 {paidCount} / {schedule.length} 回{loan.dueNow > 0 && <> ・ 期日到来分 <strong className="minus">{formatYen(loan.dueNow)}</strong></>}</span>
          </div>
          <table>
            <thead><tr><th>回</th><th>返済日</th><th className="num">元金</th><th className="num">利息</th><th className="num">合計</th><th className="num">入金済</th><th>状態</th></tr></thead>
            <tbody>
              {visible.map((s) => (
                <tr key={s.installmentNo}>
                  <td>{s.installmentNo}</td>
                  <td>{formatDate(s.dueDate)}</td>
                  <td className="num">{formatYen(s.principalDue)}</td>
                  <td className="num">{formatYen(s.interestDue)}</td>
                  <td className="num">{formatYen(Number(s.principalDue) + Number(s.interestDue))}</td>
                  <td className="num">{formatYen(Number(s.principalPaid) + Number(s.interestPaid))}{Number(s.lateFeePaid) > 0 && <span className="muted"> +損害金 {formatYen(s.lateFeePaid)}</span>}</td>
                  <td><span className={`badge badge-${s.status === "PAID" ? "SUCCESS" : s.status === "OVERDUE" ? "FAILURE" : "PENDING"}`}>{scheduleLabel[s.status]}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="pager">
            <span className="muted">{showAll ? `全 ${schedule.length} 回` : "未返済の直近 12 回"}</span>
            <button className="btn btn-secondary btn-small" type="button" onClick={() => setShowAll(!showAll)}>{showAll ? "直近のみ" : "すべて表示"}</button>
          </div>
        </section>
      )}

      {loan.collaterals.length > 0 && (
        <section className="card table-wrap">
          <h2>担保・保証</h2>
          <table>
            <tbody>
              {loan.collaterals.map((c: Row) => (
                <tr key={c.collateralId}><td>{c.collateralType}</td><td style={{ whiteSpace: "normal" }}>{c.description}</td><td className="num">{formatYen(c.appraisalValue)}</td></tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      <section className="card table-wrap">
        <h2>取引履歴</h2>
        {loan.transactions.length === 0 ? <p className="empty">取引はありません。</p> : (
          <table>
            <thead><tr><th>日時</th><th>種類</th><th className="num">元金</th><th className="num">利息</th><th className="num">損害金</th><th className="num">残高</th><th>摘要</th></tr></thead>
            <tbody>
              {loan.transactions.map((t: Row) => (
                <tr key={t.loanTxId}>
                  <td className="muted">{formatDateTime(t.createdAt)}</td><td>{t.txType}</td>
                  <td className="num">{formatYen(t.principal)}</td><td className="num">{formatYen(t.interest)}</td>
                  <td className="num">{formatYen(t.lateFee)}</td><td className="num">{formatYen(t.outstandingAfter)}</td>
                  <td>{t.memo}（{t.createdBy}）</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {staff && (live || loan.collectionActions.length > 0) && <CollectionSection loan={loan} run={run} />}

      {supervisor && live && (
        <section className="card">
          <h2>債権管理（役席）</h2>
          <div className="actions">
            <select defaultValue={loan.borrowerClass} onChange={(e) => run(() => loanApi.setClass(id, e.target.value), "債務者区分を変更しました")}>
              {Object.entries(borrowerClassLabel).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
            <button className="btn btn-danger" type="button" disabled={!["DOUBTFUL", "DEFACTO", "BANKRUPT"].includes(loan.borrowerClass)}
              onClick={() => run(() => loanApi.writeOff(id, "回収見込みなしと判断"), "償却しました")}>
              償却（破綻懸念先以下）
            </button>
          </div>
        </section>
      )}
    </>
  );
}

function PaymentForms({ loan, run }: { loan: Row; run: (fn: () => Promise<any>, done: string) => Promise<void> }) {
  const card = loan.product === "CARD";
  const [mode, setMode] = useState<"repay" | "prepay" | "draw">(card ? "draw" : "repay");
  const [amount, setAmount] = useState(loan.dueNow > 0 ? String(loan.dueNow) : "");
  const [key, setKey] = useState(newIdempotencyKey);
  const modes = card ? (["draw", "repay"] as const) : (["repay", "prepay"] as const);
  const label = { repay: "返済", prepay: "繰上返済（一部）", draw: "借入" };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const a = normalizeAmount(amount);
    const fn = mode === "draw" ? loanApi.draw : mode === "prepay" ? loanApi.prepay : loanApi.repay;
    await run(() => fn(String(loan.loanId), a, key).catch((err) => {
      if (err instanceof ApiError && err.code === "DUPLICATE_REQUEST") setKey(newIdempotencyKey());
      throw err;
    }), `${label[mode]}しました`);
    setKey(newIdempotencyKey());
  };

  return (
    <section className="card">
      <div className="tabs" role="tablist">
        {modes.map((m) => (
          <button key={m} type="button" role="tab" className="tab" aria-selected={mode === m} onClick={() => setMode(m)}>{label[m]}</button>
        ))}
      </div>
      <p className="section-note">
        {mode === "repay" && (card ? "利息 → 元金の順に充当します。" : "期日の到来した分（延滞分を含む）に、遅延損害金 → 利息 → 元金の順で充当します。")}
        {mode === "prepay" && "元金の一部を返済し、残りの回数はそのままで毎回の返済額を減らします（延滞中は不可）。"}
        {mode === "draw" && `ご利用可能額 ${formatYen(Number(loan.creditLimit) - Number(loan.outstanding))}。返済用口座 ${loan.repaymentAccountNo} へ入金します。`}
      </p>
      <form className="filters" onSubmit={submit}>
        <label>
          金額（円）
          <input inputMode="numeric" required maxLength={12} value={amount} onChange={(e) => setAmount(e.target.value)} />
        </label>
        <div className="actions"><button className="btn" type="submit">{label[mode]}する</button></div>
      </form>
    </section>
  );
}

function CollectionSection({ loan, run }: { loan: Row; run: (fn: () => Promise<any>, done: string) => Promise<void> }) {
  const [type, setType] = useState("CALL");
  const [note, setNote] = useState("");
  const [promised, setPromised] = useState("");
  return (
    <section className="card">
      <h2>延滞管理・督促</h2>
      <form className="filters" onSubmit={(e) => {
        e.preventDefault();
        void run(() => loanApi.collection({ loanId: String(loan.loanId), actionType: type, note, promisedDate: promised || undefined }), "記録しました");
        setNote("");
      }}>
        <label>
          対応
          <select value={type} onChange={(e) => setType(e.target.value)}>
            {Object.entries(actionLabel).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
        <label style={{ flex: "1 1 280px" }}>
          内容
          <input required maxLength={400} value={note} onChange={(e) => setNote(e.target.value)} />
        </label>
        <label>
          約束日
          <input type="date" value={promised} onChange={(e) => setPromised(e.target.value)} />
        </label>
        <div className="actions"><button className="btn btn-small" type="submit">記録</button></div>
      </form>
      {loan.collectionActions.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>日時</th><th>対応</th><th>内容</th><th>約束日</th><th>担当</th></tr></thead>
            <tbody>
              {loan.collectionActions.map((a: Row) => (
                <tr key={a.actionId}>
                  <td className="muted">{formatDateTime(a.createdAt)}</td><td>{actionLabel[a.actionType]}</td>
                  <td style={{ whiteSpace: "normal" }}>{a.note}</td><td>{formatDate(a.promisedDate)}</td><td>{a.createdBy}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

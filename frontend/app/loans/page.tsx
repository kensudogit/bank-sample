"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/components/AuthProvider";
import { ErrorBox, Loading } from "@/components/Feedback";
import { loanApi, useLoanApplications, useLoans } from "@/lib/banking";
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

export default function LoansPage() {
  const { user } = useAuth();
  const [tab, setTab] = useState<"loans" | "apps" | "apply">("loans");
  return (
    <>
      <div className="page-head">
        <div>
          <p className="eyebrow">Loans</p>
          <h1>融資</h1>
          <p>申込 → 審査 → 承認 → 担保設定・契約 → 実行 → 返済。{user?.customer ? "お申込みはオンラインで受け付けます。" : ""}</p>
        </div>
        <Link className="btn btn-secondary" href="/loans/simulate">返済シミュレーション</Link>
      </div>
      <div className="tabs" role="tablist">
        {([["loans", "融資一覧"], ["apps", "申込一覧"], ["apply", "新規申込"]] as const).map(([t, label]) => (
          <button key={t} type="button" role="tab" className="tab" aria-selected={tab === t} onClick={() => setTab(t)}>{label}</button>
        ))}
      </div>
      {tab === "loans" && <LoanList />}
      {tab === "apps" && <ApplicationList />}
      {tab === "apply" && <ApplyForm onDone={() => setTab("apps")} />}
    </>
  );
}

function LoanList() {
  const { data, error } = useLoans();
  return (
    <section className="card table-wrap">
      <ErrorBox error={error} />
      {!data && !error && <Loading />}
      {data && data.length === 0 && <p className="empty">融資はありません。</p>}
      {data && data.length > 0 && (
        <table>
          <thead>
            <tr><th>No.</th><th>お客さま</th><th>商品</th><th className="num">残高</th><th className="num">極度額</th><th className="num">金利</th><th>状態</th><th>債務者区分</th><th>完済予定</th></tr>
          </thead>
          <tbody>
            {data.map((l) => (
              <tr key={l.loanId}>
                <td><Link href={`/loans/${l.loanId}`}>{l.loanId}</Link></td>
                <td>{l.customerName}<span className="muted"> {l.customerId}</span></td>
                <td>{productLabel[l.product]}</td>
                <td className="num">{l.product === "OVERDRAFT" ? "—" : formatYen(l.outstanding)}</td>
                <td className="num">{l.creditLimit ? formatYen(l.creditLimit) : "—"}</td>
                <td className="num">{Number(l.annualRate).toFixed(3)}%</td>
                <td><span className={`badge badge-${l.status === "ACTIVE" ? "SUCCESS" : l.status === "DELINQUENT" || l.status === "WRITTEN_OFF" ? "FAILURE" : "PENDING"}`}>{loanStatusLabel[l.status]}</span>{l.daysPastDue > 0 && <span className="minus"> {l.daysPastDue}日延滞</span>}</td>
                <td>{borrowerClassLabel[l.borrowerClass]}</td>
                <td>{formatDate(l.maturityDate)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function ApplicationList() {
  const { data, error } = useLoanApplications();
  return (
    <section className="card table-wrap">
      <ErrorBox error={error} />
      {!data && !error && <Loading />}
      {data && data.length === 0 && <p className="empty">申込はありません。</p>}
      {data && data.length > 0 && (
        <table>
          <thead><tr><th>No.</th><th>受付</th><th>お客さま</th><th>商品</th><th className="num">金額</th><th>期間</th><th>状態</th><th>格付</th><th>推奨</th></tr></thead>
          <tbody>
            {data.map((a) => (
              <tr key={a.applicationId}>
                <td><Link href={`/loans/applications/${a.applicationId}`}>{a.applicationId}</Link></td>
                <td className="muted">{formatDateTime(a.appliedAt)}</td>
                <td>{a.customerName}<span className="muted"> {a.customerId}</span></td>
                <td>{productLabel[a.product]}</td>
                <td className="num">{formatYen(a.requestedAmount)}</td>
                <td>{a.termMonths}か月</td>
                <td><span className="badge badge-PENDING">{loanStatusLabel[a.status]}</span></td>
                <td>{a.grade ?? "—"}{a.score != null && <span className="muted"> ({a.score})</span>}</td>
                <td>{a.recommendation === "APPROVE" ? "承認" : a.recommendation === "REVIEW" ? "要検討" : a.recommendation === "DECLINE" ? "謝絶" : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

const PRODUCTS: Record<string, { label: string; note: string; corporate: boolean; term: boolean }> = {
  HOUSING: { label: "住宅ローン", note: "個人・不動産担保。5〜35年。完済時80歳まで。", corporate: false, term: true },
  CARD: { label: "カードローン", note: "個人・極度額10万〜500万円（年収の3分の1まで：総量規制）。随時借入・返済。", corporate: false, term: false },
  BUSINESS: { label: "事業融資", note: "法人の運転資金・設備資金。6か月〜10年。", corporate: true, term: true },
  OVERDRAFT: { label: "当座貸越", note: "法人・当座預金の極度額。契約期間1年。", corporate: true, term: false },
  SYNDICATED: { label: "シンジケートローン", note: "法人・複数金融機関による協調融資（当行はアレンジャー）。", corporate: true, term: true },
};

function ApplyForm({ onDone }: { onDone: () => void }) {
  const { user } = useAuth();
  const router = useRouter();
  const [form, setForm] = useState({ customerId: user?.customerId ?? "", product: user?.customer ? "CARD" : "HOUSING", amount: "",
    termMonths: "240", repaymentMethod: "EQUAL_PAYMENT", purpose: "", annualIncome: "", existingDebtAnnual: "0", collateralValue: "",
    syndicateTotal: "" });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const p = PRODUCTS[form.product];
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm({ ...form, [k]: e.target.value });
  const choices = Object.entries(PRODUCTS).filter(([, v]) => !user?.customer || !v.corporate);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      const body: Record<string, string> = { ...form };
      for (const k of ["amount", "annualIncome", "existingDebtAnnual", "collateralValue", "syndicateTotal"]) {
        body[k] = normalizeAmount(body[k]);
      }
      const app = await loanApi.apply(body);
      if (user?.staff) router.push(`/loans/applications/${app.applicationId}`);
      else onDone();
    } catch (e2) {
      setErr(e2);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card">
      <h2>融資の申込</h2>
      <ErrorBox error={err} />
      <form className="wide" onSubmit={submit}>
        <div className="form-grid">
          {user?.staff && (
            <label>
              顧客番号
              <input required maxLength={10} value={form.customerId} onChange={set("customerId")} placeholder="CU0001" />
            </label>
          )}
          <label>
            商品
            <select value={form.product} onChange={set("product")}>
              {choices.map(([k, v]) => <option key={k} value={k}>{v.label}{v.corporate ? "（法人）" : ""}</option>)}
            </select>
            <span className="hint">{p.note}</span>
          </label>
          <label>
            {p.term ? "借入額（円）" : "ご利用限度額（円）"}
            <input inputMode="numeric" required maxLength={13} value={form.amount} onChange={set("amount")} />
          </label>
          {p.term && (
            <>
              <label>
                期間（月）
                <input inputMode="numeric" required maxLength={3} value={form.termMonths} onChange={set("termMonths")} />
              </label>
              <label>
                返済方法
                <select value={form.repaymentMethod} onChange={set("repaymentMethod")}>
                  <option value="EQUAL_PAYMENT">{methodLabel.EQUAL_PAYMENT}（毎回の返済額が一定）</option>
                  <option value="EQUAL_PRINCIPAL">{methodLabel.EQUAL_PRINCIPAL}（元金が一定・総利息が少ない）</option>
                </select>
              </label>
            </>
          )}
          <label>
            {p.corporate ? "年間営業キャッシュフロー（円）" : "年収（税込・円）"}
            <input inputMode="numeric" required maxLength={13} value={form.annualIncome} onChange={set("annualIncome")} />
          </label>
          <label>
            他の借入の年間返済額（円）
            <input inputMode="numeric" maxLength={13} value={form.existingDebtAnnual} onChange={set("existingDebtAnnual")} />
          </label>
          {(form.product === "HOUSING" || form.product === "BUSINESS" || form.product === "SYNDICATED") && (
            <label>
              担保評価額（円）
              <input inputMode="numeric" required={form.product === "HOUSING"} maxLength={13} value={form.collateralValue}
                onChange={set("collateralValue")} />
            </label>
          )}
          {form.product === "SYNDICATED" && (
            <label>
              組成総額（円）
              <input inputMode="numeric" required maxLength={13} value={form.syndicateTotal} onChange={set("syndicateTotal")} />
              <span className="hint">借入額は当行の分担額</span>
            </label>
          )}
        </div>
        <label>
          資金使途
          <input maxLength={200} value={form.purpose} onChange={set("purpose")} placeholder="自宅マンション購入 / 運転資金 など" />
        </label>
        <div className="actions"><button className="btn" type="submit" disabled={busy}>{busy ? "送信中…" : "申し込む"}</button></div>
      </form>
    </section>
  );
}

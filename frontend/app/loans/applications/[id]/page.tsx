"use client";

// 融資申込の詳細画面（/loans/applications/[id]）。与信審査（スコア・格付・DTI・LTV）、役席の承認/謝絶、
// 担保・保証やシンジケート参加行の登録、契約締結、融資実行までの一連の手続きを行う。

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { IconArrowLeft } from "@/components/Icons";
import { loanApi, useLoanApplication, type Row } from "@/lib/banking";
import { formatDateTime, formatPercent, formatYen, loanStatusLabel, methodLabel, normalizeAmount, productLabel } from "@/lib/format";
import { newIdempotencyKey } from "@/lib/api";

/** 担保・保証の種類 → 日本語表示名 */
const collateralLabel: Record<string, string> = {
  REAL_ESTATE: "不動産（抵当権）",
  DEPOSIT: "預金担保",
  SECURITIES: "有価証券",
  GUARANTOR: "保証人",
  GUARANTEE_CO: "保証会社",
};

/** 申込詳細ページ。状態（申込受付 → 審査済 → 承認 → 契約済）と役割に応じて操作を出し分ける。 */
export default function ApplicationPage() {
  const { id } = useParams<{ id: string }>();
  const { user } = useAuth();
  const { data: app, error } = useLoanApplication(id);
  const [err, setErr] = useState<unknown>(null);
  const [message, setMessage] = useState<string | null>(null);

  // 各操作ボタン共通の実行ヘルパー（成功メッセージかエラーを表示する）
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

  if (!app) return error ? <ErrorBox error={error} /> : <Loading />;
  const staff = user?.staff ?? false;
  const supervisor = user?.role === "SUPERVISOR";
  // 担保や参加行を追加できるのは契約前（申込受付・審査済・承認）まで
  const editable = ["APPLIED", "SCREENED", "APPROVED"].includes(app.status);

  return (
    <>
      <Link href="/loans" className="back-link"><IconArrowLeft size={16} /> 融資</Link>
      <section className="hero">
        <div className="hero-label">融資申込 No.{app.applicationId} ・ {productLabel[app.product]}</div>
        <div className="hero-amount">{formatYen(app.requestedAmount)}</div>
        <div className="hero-meta">
          <span>お客さま <strong>{app.customerName}（{app.customerId}）</strong></span>
          <span>状態 <strong>{loanStatusLabel[app.status]}</strong></span>
          <span>期間 <strong>{app.termMonths}か月</strong></span>
          <span>返済 <strong>{methodLabel[app.repaymentMethod]}</strong></span>
          {app.syndicateTotal && <span>組成総額 <strong>{formatYen(app.syndicateTotal)}</strong></span>}
        </div>
      </section>
      <ErrorBox error={error ?? err} />
      {message && <SuccessBox>{message}</SuccessBox>}

      <section className="card">
        <h2>与信審査</h2>
        <dl className="stats">
          <div className="stat"><dt>年収・営業CF</dt><dd>{formatYen(app.annualIncome)}</dd></div>
          <div className="stat"><dt>他の借入の年間返済</dt><dd>{formatYen(app.existingDebtAnnual)}</dd></div>
          <div className="stat"><dt>担保評価額（申告）</dt><dd>{formatYen(app.collateralValue)}</dd></div>
          {app.score != null && (
            <>
              <div className="stat"><dt>スコア／格付</dt><dd>{app.score} 点 ／ {app.grade}</dd></div>
              <div className="stat"><dt>返済比率（DTI）</dt><dd>{formatPercent(Number(app.dti))}</dd></div>
              <div className="stat"><dt>担保掛目（LTV）</dt><dd>{app.ltv != null ? formatPercent(Number(app.ltv)) : "—"}</dd></div>
              <div className="stat"><dt>提示金利</dt><dd>{Number(app.proposedRate).toFixed(3)}%</dd></div>
              <div className="stat">
                <dt>推奨</dt>
                <dd className={app.recommendation === "DECLINE" ? "minus" : app.recommendation === "APPROVE" ? "plus" : ""}>
                  {app.recommendation === "APPROVE" ? "承認" : app.recommendation === "REVIEW" ? "要検討" : "謝絶"}
                </dd>
              </div>
            </>
          )}
        </dl>
        {app.screeningNote && <p className="section-note">審査メモ: {app.screeningNote}</p>}
        <p className="muted" style={{ fontSize: "0.82rem" }}>
          受付 {app.appliedBy}（{formatDateTime(app.appliedAt)}）{app.screenedBy && ` ／ 審査 ${app.screenedBy}`}
          {app.decidedBy && ` ／ 判断 ${app.decidedBy}：${app.decisionNote ?? ""}`}
        </p>
        <div className="actions">
          {staff && ["APPLIED", "SCREENED"].includes(app.status) && (
            <button className="btn btn-secondary" type="button" onClick={() => run(() => loanApi.screen(id), "審査しました")}>
              {app.status === "APPLIED" ? "審査する" : "再審査"}
            </button>
          )}
        </div>
        {/* 審査済みの申込は役席が承認・謝絶を判断する */}
        {supervisor && app.status === "SCREENED" && <DecisionForm id={id} app={app} run={run} />}
      </section>

      <section className="card">
        <h2>担保・保証</h2>
        {app.collaterals.length === 0 ? <p className="empty">登録なし</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>種類</th><th>内容</th><th className="num">評価額</th><th>順位</th><th>保証人</th></tr></thead>
              <tbody>
                {app.collaterals.map((c: Row) => (
                  <tr key={c.collateralId}>
                    <td>{collateralLabel[c.collateralType]}</td><td style={{ whiteSpace: "normal" }}>{c.description}</td>
                    <td className="num">{formatYen(c.appraisalValue)}</td><td>{c.lienRank ? `第${c.lienRank}順位` : "—"}</td>
                    <td>{c.guarantorName ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {staff && editable && <CollateralForm id={id} run={run} />}
      </section>

      {/* シンジケートローンのみ：参加金融機関と分担額 */}
      {app.product === "SYNDICATED" && (
        <section className="card">
          <h2>シンジケート団（参加金融機関）</h2>
          {app.participants.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead><tr><th>金融機関</th><th>役割</th><th className="num">分担額</th><th className="num">シェア</th></tr></thead>
                <tbody>
                  {app.participants.map((p: Row) => (
                    <tr key={p.lenderName}>
                      <td>{p.lenderName}</td><td>{p.role === "ARRANGER" ? "アレンジャー" : "参加行"}</td>
                      <td className="num">{formatYen(p.shareAmount)}</td>
                      <td className="num">{formatPercent(Number(p.shareAmount) / Number(app.syndicateTotal))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {staff && editable && <ParticipantForm id={id} run={run} />}
        </section>
      )}

      {staff && app.status === "APPROVED" && <ContractForm id={id} run={run} />}
      {app.loanId && (
        <section className="card">
          <h2>契約</h2>
          <p>融資 <Link href={`/loans/${app.loanId}`}>No.{app.loanId}</Link> {app.status === "CONTRACTED" ? "（実行待ち）" : ""}</p>
          {/* 融資実行（融資金を返済用口座へ入金する）。実行ごとに新しい冪等キーを付けて送る */}
          {staff && app.status === "CONTRACTED" && (
            <button className="btn" type="button" onClick={() => run(() => loanApi.disburse(String(app.loanId), newIdempotencyKey()), "融資を実行しました")}>
              融資を実行する
            </button>
          )}
        </section>
      )}
    </>
  );
}

/** 親から受け取る実行ヘルパーの型 */
type Run = (fn: () => Promise<any>, done: string) => Promise<void>;

/** 承認・謝絶のフォーム（役席）。承認時は金利を調整できる。謝絶推奨の案件を承認する場合は理由が必須（サーバー側で確認）。 */
function DecisionForm({ id, app, run }: { id: string; app: Row; run: Run }) {
  const [note, setNote] = useState("");
  const [rate, setRate] = useState(String(app.proposedRate ?? ""));
  return (
    <form className="filters" style={{ marginTop: 16 }} onSubmit={(e) => e.preventDefault()}>
      <label style={{ flex: "1 1 280px" }}>
        判断理由{app.recommendation === "DECLINE" ? "（謝絶推奨のため承認には必須）" : ""}
        <input maxLength={400} value={note} onChange={(e) => setNote(e.target.value)} />
      </label>
      <label>
        承認金利（%）
        <input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} />
      </label>
      <div className="actions">
        <button className="btn" type="button" onClick={() => run(() => loanApi.decide({ applicationId: id, decision: "APPROVE", note, rate }), "承認しました")}>承認</button>
        <button className="btn btn-danger" type="button" onClick={() => run(() => loanApi.decide({ applicationId: id, decision: "REJECT", note }), "謝絶しました")}>謝絶</button>
      </div>
    </form>
  );
}

/** 担保・保証の登録フォーム。種類に応じて抵当権の順位や保証人名の入力欄を出す。 */
function CollateralForm({ id, run }: { id: string; run: Run }) {
  const [f, setF] = useState({ collateralType: "REAL_ESTATE", description: "", appraisalValue: "", lienRank: "1", guarantorName: "" });
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value });
  return (
    <form className="filters" style={{ marginTop: 16 }} onSubmit={(e) => {
      e.preventDefault();
      void run(() => loanApi.collateral({ applicationId: id, ...f, appraisalValue: normalizeAmount(f.appraisalValue) }), "担保・保証を登録しました");
    }}>
      <label>
        種類
        <select value={f.collateralType} onChange={set("collateralType")}>
          {Object.entries(collateralLabel).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </label>
      <label style={{ flex: "1 1 240px" }}>
        内容
        <input required maxLength={200} value={f.description} onChange={set("description")} placeholder="所在地・物件 / 保証会社名" />
      </label>
      <label>
        評価額（円）
        <input inputMode="numeric" maxLength={13} value={f.appraisalValue} onChange={set("appraisalValue")} />
      </label>
      {f.collateralType === "REAL_ESTATE" && (
        <label>
          抵当権の順位
          <input inputMode="numeric" maxLength={1} value={f.lienRank} onChange={set("lienRank")} />
        </label>
      )}
      {(f.collateralType === "GUARANTOR" || f.collateralType === "GUARANTEE_CO") && (
        <label>
          保証人・保証会社
          <input maxLength={100} value={f.guarantorName} onChange={set("guarantorName")} />
        </label>
      )}
      <div className="actions"><button className="btn btn-small" type="submit">登録</button></div>
    </form>
  );
}

/** シンジケートローンの参加金融機関（分担額・役割）の登録フォーム */
function ParticipantForm({ id, run }: { id: string; run: Run }) {
  const [f, setF] = useState({ lenderName: "", shareAmount: "", role: "PARTICIPANT" });
  return (
    <form className="filters" style={{ marginTop: 16 }} onSubmit={(e) => {
      e.preventDefault();
      void run(() => loanApi.participant({ applicationId: id, ...f, shareAmount: normalizeAmount(f.shareAmount) }), "参加行を登録しました");
    }}>
      <label>
        金融機関名
        <input required maxLength={100} value={f.lenderName} onChange={(e) => setF({ ...f, lenderName: e.target.value })} placeholder="永劫銀行（当行）" />
      </label>
      <label>
        分担額（円）
        <input inputMode="numeric" required maxLength={13} value={f.shareAmount} onChange={(e) => setF({ ...f, shareAmount: e.target.value })} />
      </label>
      <label>
        役割
        <select value={f.role} onChange={(e) => setF({ ...f, role: e.target.value })}>
          <option value="ARRANGER">アレンジャー</option>
          <option value="PARTICIPANT">参加行</option>
        </select>
      </label>
      <div className="actions"><button className="btn btn-small" type="submit">追加</button></div>
    </form>
  );
}

/** 契約締結フォーム：返済用口座と毎月の返済日を指定して契約する。 */
function ContractForm({ id, run }: { id: string; run: Run }) {
  const [accountNo, setAccountNo] = useState("");
  const [day, setDay] = useState("27");
  return (
    <section className="card">
      <h2>契約締結</h2>
      <p className="section-note">返済用口座（融資金の入金先・約定返済の引落口座）と毎月の返済日を指定します。当座貸越は当座預金口座を指定します。</p>
      <form className="filters" onSubmit={(e) => {
        e.preventDefault();
        void run(() => loanApi.contract({ applicationId: id, repaymentAccountNo: accountNo, paymentDay: day }), "契約しました");
      }}>
        <label>
          返済用口座
          <input inputMode="numeric" required maxLength={7} value={accountNo} onChange={(e) => setAccountNo(e.target.value)} />
        </label>
        <label>
          返済日（毎月）
          <input inputMode="numeric" required maxLength={2} value={day} onChange={(e) => setDay(e.target.value)} />
        </label>
        <div className="actions"><button className="btn" type="submit">契約する</button></div>
      </form>
    </section>
  );
}

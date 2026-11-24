"use client";

// 為替・口座振替の画面（/payments）。他行宛振込、口座振替（契約・請求取込・引落処理）、海外送金（制裁リスト照合と保留）、
// 役席向けの全銀データ作成（仕向）と他行からの振込（被仕向）の入金処理を、タブで切り替えて扱う。

import { useState } from "react";
import { AccountPicker } from "@/components/AccountPicker";
import { useAuth } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { ApiError, newIdempotencyKey } from "@/lib/api";
import {
  paymentApi,
  useBanks,
  useBillers,
  useDebitRequests,
  useForeignRemittances,
  useMandates,
  useRemittances,
  useZenginBatches,
  type Row,
} from "@/lib/banking";
import { formatDate, formatDateTime, formatMoney, formatYen, normalizeAmount, remitStatusLabel, todayIso } from "@/lib/format";

/** タブの種類。全銀・被仕向は役席のみ表示する。 */
type Tab = "domestic" | "debit" | "foreign" | "zengin";

/** 為替ページ。役割に応じてタブを追加する。 */
export default function PaymentsPage() {
  const { user } = useAuth();
  const [tab, setTab] = useState<Tab>("domestic");
  const tabs: [Tab, string][] = [
    ["domestic", "他行宛振込"],
    ["debit", "口座振替"],
    ["foreign", "海外送金"],
  ];
  if (user?.role === "SUPERVISOR") tabs.push(["zengin", "全銀・被仕向"]);
  return (
    <>
      <div className="page-head">
        <div>
          <p className="eyebrow">Payments</p>
          <h1>為替（送金・口座振替）</h1>
          <p>当行あての振込・本人口座間の振替は「振込・振替」から。他行あては全銀システム経由で送金します。</p>
        </div>
      </div>
      <div className="tabs" role="tablist">
        {tabs.map(([t, label]) => (
          <button key={t} type="button" role="tab" className="tab" aria-selected={tab === t} onClick={() => setTab(t)}>
            {label}
          </button>
        ))}
      </div>
      {tab === "domestic" && <DomesticRemittance />}
      {tab === "debit" && <DirectDebit />}
      {tab === "foreign" && <ForeignRemittance />}
      {tab === "zengin" && <ZenginAndIncoming />}
    </>
  );
}

/** 送金・振替のステータスを色分けしたバッジで表示する（完了系＝成功、保留・待ち＝待機、それ以外＝失敗）。 */
function StatusBadge({ status }: { status: string }) {
  const tone = ["SENT", "SETTLED", "CREDITED", "PAID", "ACTIVE"].includes(status) ? "SUCCESS"
    : ["HELD", "PENDING"].includes(status) ? "PENDING" : "FAILURE";
  return <span className={`badge badge-${tone}`}>{remitStatusLabel[status] ?? status}</span>;
}

// ---------------------------------------------------------------- domestic
/** 他行宛振込：受付フォームと履歴（送信前の取消、送信後の組戻し）。冪等キーで二重振込を防ぐ。 */
function DomesticRemittance() {
  const { user } = useAuth();
  const { data: banks } = useBanks();
  const { data: list, error } = useRemittances("OUT");
  const [form, setForm] = useState({ accountNo: "", bankCode: "0005", branchCode: "", destType: "1", destAccountNo: "",
    destNameKana: "", amount: "" });
  const [key, setKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const [sent, setSent] = useState<Row | null>(null);
  const amount = Number(normalizeAmount(form.amount) || 0);
  // 振込手数料の目安表示（サーバー側の料金表と同じ：3 万円未満 330 円、以上 440 円）
  const fee = amount > 0 ? (amount < 30000 ? 330 : 440) : 0;
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm({ ...form, [k]: e.target.value });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      setSent(await paymentApi.send({ ...form, amount: normalizeAmount(form.amount), idempotencyKey: key }));
      setForm({ ...form, destAccountNo: "", destNameKana: "", amount: "" });
      setKey(newIdempotencyKey());
    } catch (e2) {
      setErr(e2);
      if (e2 instanceof ApiError && e2.code === "DUPLICATE_REQUEST") setKey(newIdempotencyKey());
    } finally {
      setBusy(false);
    }
  };

  const act = async (fn: () => Promise<unknown>) => {
    setErr(null);
    try {
      await fn();
    } catch (e2) {
      setErr(e2);
    }
  };

  return (
    <>
      <section className="card">
        <h2>他行宛振込</h2>
        <p className="section-note">手数料: 3万円未満 330円・3万円以上 440円。平日15時以降・休業日の受付は翌営業日の振込になります。</p>
        <ErrorBox error={err} />
        {sent && (
          <SuccessBox>
            受け付けました（No.{sent.remitId}）：{sent.bankName} 宛 {formatYen(sent.amount)}、手数料 {formatYen(sent.fee)}、
            振込指定日 {sent.valueDate}、受取人 {sent.destNameKana}
          </SuccessBox>
        )}
        <form className="wide" onSubmit={submit}>
          <div className="form-grid">
            <AccountPicker label="お支払口座" value={form.accountNo} onChange={(v) => setForm({ ...form, accountNo: v })} />
            <label>
              振込先金融機関
              <select value={form.bankCode} onChange={set("bankCode")}>
                {/* 9999 は当行自身のコード。当行宛は「振込・振替」画面で行うため選択肢から除く */}
                {(banks ?? []).filter((b) => b.bankCode !== "9999").map((b) => (
                  <option key={b.bankCode} value={b.bankCode}>{b.bankCode} {b.name}</option>
                ))}
              </select>
            </label>
            <label>
              支店番号
              <input inputMode="numeric" required maxLength={3} value={form.branchCode} onChange={set("branchCode")} placeholder="123" />
            </label>
            <label>
              預金種目
              <select value={form.destType} onChange={set("destType")}>
                <option value="1">普通</option>
                <option value="2">当座</option>
                <option value="4">貯蓄</option>
              </select>
            </label>
            <label>
              口座番号
              <input inputMode="numeric" required maxLength={7} value={form.destAccountNo} onChange={set("destAccountNo")} placeholder="7654321" />
            </label>
            <label>
              受取人名（カナ）
              <input required maxLength={30} value={form.destNameKana} onChange={set("destNameKana")} placeholder="タナカ ハナコ" />
            </label>
            <label>
              金額（円）
              <input inputMode="numeric" required maxLength={12} value={form.amount} onChange={set("amount")} />
              {fee > 0 && <span className="hint">手数料 {formatYen(fee)}・お引落し合計 {formatYen(amount + fee)}</span>}
            </label>
          </div>
          <div className="actions">
            <button className="btn" type="submit" disabled={busy}>{busy ? "処理中…" : "振込を受け付ける"}</button>
          </div>
        </form>
      </section>

      <section className="card table-wrap">
        <h2>他行宛振込の履歴</h2>
        <ErrorBox error={error} />
        {!list && !error && <Loading />}
        {list && list.length === 0 && <p className="empty">履歴はありません。</p>}
        {list && list.length > 0 && (
          <table>
            <thead>
              <tr><th>No.</th><th>受付</th><th>支払口座</th><th>振込先</th><th>受取人</th><th className="num">金額</th><th className="num">手数料</th><th>指定日</th><th>状態</th><th /></tr>
            </thead>
            <tbody>
              {list.map((r) => (
                <tr key={r.remitId}>
                  <td>{r.remitId}</td>
                  <td className="muted">{formatDateTime(r.createdAt)}</td>
                  <td>{r.accountNo}</td>
                  <td>{r.bankName} {r.branchCode}-{r.destAccountNo}</td>
                  <td>{r.destNameKana}</td>
                  <td className="num">{formatYen(r.amount)}</td>
                  <td className="num">{formatYen(r.fee)}</td>
                  <td>{formatDate(r.valueDate)}</td>
                  <td><StatusBadge status={r.status} /></td>
                  <td>
                    {/* 送信待ちは取消、送信済み・決済済みは行員が組戻しを行う */}
                    {r.status === "PENDING" && (
                      <button className="btn btn-secondary btn-small" type="button" onClick={() => act(() => paymentApi.cancel(String(r.remitId)))}>
                        取消
                      </button>
                    )}
                    {user?.staff && (r.status === "SENT" || r.status === "SETTLED") && (
                      <button className="btn btn-secondary btn-small" type="button"
                        onClick={() => act(() => paymentApi.returnRemit(String(r.remitId), "お客さまの依頼による組戻し"))}>
                        組戻し
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}

// ---------------------------------------------------------------- direct debit
/** 口座振替：契約の登録・解約、収納企業の請求データ取込（行員）、引落日到来分の処理（役席）、引落し状況。 */
function DirectDebit() {
  const { user } = useAuth();
  const { data: billers } = useBillers();
  const { data: mandates, error } = useMandates();
  const { data: requests } = useDebitRequests();
  const [accountNo, setAccountNo] = useState("");
  const [billerCode, setBillerCode] = useState("ELEC01");
  const [payer, setPayer] = useState("");
  const [billing, setBilling] = useState("");
  const [err, setErr] = useState<unknown>(null);
  const [message, setMessage] = useState<string | null>(null);

  const run = async (fn: () => Promise<unknown>, done: (r: any) => string) => {
    setErr(null);
    setMessage(null);
    try {
      setMessage(done(await fn()));
    } catch (e2) {
      setErr(e2);
    }
  };

  // 貼り付けた CSV テキストを 1 行ずつ請求データに変換してサーバーへ送る（同じ請求の再取込はサーバー側で重複扱い）
  const importBilling = () => {
    // CSV: billerCode,payerNumber,amount,dueDate
    const items = billing.split(/\r?\n/).map((l) => l.trim()).filter(Boolean).map((l) => {
      const [b, p, a, d] = l.split(",").map((x) => x.trim());
      return { billerCode: b, payerNumber: p, amount: a, dueDate: d };
    });
    return run(() => paymentApi.importBilling(items),
      (r: Row[]) => `取込結果: ${r.map((x) => `${x.billerCode}/${x.payerNumber} ${x.result}`).join("、")}`);
  };

  return (
    <>
      <section className="card">
        <h2>口座振替の契約</h2>
        <p className="section-note">公共料金などを指定口座から自動で引き落とします。請求データは収納企業から取り込み、引落日に処理します（残高不足は振替不能）。</p>
        <ErrorBox error={error ?? err} />
        {message && <SuccessBox>{message}</SuccessBox>}
        <form className="filters" onSubmit={(e) => {
          e.preventDefault();
          void run(() => paymentApi.createMandate(accountNo, billerCode, payer), (r) => `口座振替を登録しました（${r.billerName}・No.${r.mandateId}）`);
        }}>
          <AccountPicker label="引落口座" value={accountNo} onChange={setAccountNo} />
          <label>
            収納企業
            <select value={billerCode} onChange={(e) => setBillerCode(e.target.value)}>
              {(billers ?? []).map((b) => <option key={b.billerCode} value={b.billerCode}>{b.name}（{b.category}）</option>)}
            </select>
          </label>
          <label>
            お客さま番号
            <input required maxLength={20} value={payer} onChange={(e) => setPayer(e.target.value)} placeholder="E-0001" />
          </label>
          <div className="actions"><button className="btn btn-small" type="submit">登録</button></div>
        </form>
        {mandates && mandates.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>No.</th><th>収納企業</th><th>お客さま番号</th><th>引落口座</th><th>状態</th><th /></tr></thead>
              <tbody>
                {mandates.map((m) => (
                  <tr key={m.mandateId}>
                    <td>{m.mandateId}</td><td>{m.billerName}</td><td className="mono">{m.payerNumber}</td><td>{m.accountNo}</td>
                    <td><span className={`badge badge-${m.status === "ACTIVE" ? "SUCCESS" : "FAILURE"}`}>{m.status === "ACTIVE" ? "有効" : "解約"}</span></td>
                    <td>
                      {m.status === "ACTIVE" && (
                        <button className="btn btn-secondary btn-small" type="button"
                          onClick={() => run(() => paymentApi.cancelMandate(String(m.mandateId)), () => "口座振替を解約しました")}>
                          解約
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {user?.staff && (
        <section className="card">
          <h2>請求データの取込（収納企業から）</h2>
          <p className="section-note">1行に「収納企業コード,お客さま番号,金額,引落日」。例: ELEC01,E-0001,8000,{todayIso()}</p>
          <form onSubmit={(e) => { e.preventDefault(); void importBilling(); }} style={{ maxWidth: "none" }}>
            <textarea rows={4} value={billing} onChange={(e) => setBilling(e.target.value)} className="mono" />
            <div className="actions">
              <button className="btn btn-small" type="submit">取り込む</button>
              {user.role === "SUPERVISOR" && (
                <button className="btn btn-secondary btn-small" type="button"
                  onClick={() => run(paymentApi.processDebits, (r) => `振替処理: 振替済 ${r.paid} 件・不能 ${r.failed} 件`)}>
                  引落日の到来分を今すぐ処理
                </button>
              )}
            </div>
          </form>
        </section>
      )}

      <section className="card table-wrap">
        <h2>引落しの状況</h2>
        {requests && requests.length === 0 && <p className="empty">請求はありません。</p>}
        {requests && requests.length > 0 && (
          <table>
            <thead><tr><th>引落日</th><th>収納企業</th><th>お客さま番号</th><th>口座</th><th className="num">金額</th><th>結果</th></tr></thead>
            <tbody>
              {requests.map((r) => (
                <tr key={r.requestId}>
                  <td>{formatDate(r.dueDate)}</td><td>{r.billerName}</td><td className="mono">{r.payerNumber}</td><td>{r.accountNo}</td>
                  <td className="num">{formatYen(r.amount)}</td>
                  <td>
                    {r.status === "PENDING" ? <span className="badge badge-PENDING">引落待ち</span>
                      : r.status === "PAID" ? <span className="badge badge-SUCCESS">振替済</span>
                      : <span className="badge badge-FAILURE">不能（{r.resultCode === "1" ? "資金不足" : "取引なし"}）</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}

// ---------------------------------------------------------------- foreign
/**
 * 海外送金：送金フォーム、履歴、SWIFT MT103 電文の表示、保留案件の判断（役席）。
 * 受取人が制裁リスト・高リスク国に該当する可能性がある場合、サーバーが保留（HELD）にする。
 */
function ForeignRemittance() {
  const { user } = useAuth();
  const { data: list, error } = useForeignRemittances();
  const [form, setForm] = useState({ accountNo: "", feeAccountNo: "", currency: "USD", amount: "", beneficiaryName: "",
    beneficiaryAccount: "", swiftBic: "", country: "", purpose: "" });
  const [key, setKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const [sent, setSent] = useState<Row | null>(null);
  const [message, setMessage] = useState<Row | null>(null);
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm({ ...form, [k]: e.target.value });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      // 手数料口座が未指定なら送金元口座から引き落とす
      setSent(await paymentApi.sendForeign({ ...form, feeAccountNo: form.feeAccountNo || form.accountNo, idempotencyKey: key }));
      setKey(newIdempotencyKey());
    } catch (e2) {
      setErr(e2);
      if (e2 instanceof ApiError && e2.code === "DUPLICATE_REQUEST") setKey(newIdempotencyKey());
    } finally {
      setBusy(false);
    }
  };

  // 保留中の海外送金を、確認のうえ送金（RELEASE）または謝絶（REJECT）する
  const decide = async (remitId: string, decision: "RELEASE" | "REJECT") => {
    setErr(null);
    try {
      await paymentApi.decideForeign(remitId, decision, decision === "RELEASE" ? "確認の結果、問題なし" : "制裁リスト該当の疑いにより謝絶");
    } catch (e2) {
      setErr(e2);
    }
  };

  // 送信済みの送金の SWIFT MT103 電文を取得して表示する（失敗したらエラーを表示する）
  const showMessage = async (remitId: string) => {
    setErr(null);
    try {
      setMessage(await paymentApi.foreignOne(remitId));
    } catch (e2) {
      setMessage(null);
      setErr(e2);
    }
  };

  return (
    <>
      <section className="card">
        <h2>海外送金</h2>
        <p className="section-note">
          円口座から送る場合は TTS で換算、外貨預金からはそのまま送金します。手数料 4,000円（円口座から）。受取人は制裁リスト・高リスク国と照合し、該当の可能性がある場合は保留して確認します。
        </p>
        <ErrorBox error={err} />
        {sent && (
          <SuccessBox>
            {sent.status === "SENT" ? "送金しました" : "確認のため保留しました"}（No.{sent.remitId}）：{sent.currency}{" "}
            {formatMoney(sent.amountMinor, sent.currency)}
            {sent.rate ? `（${formatYen(sent.debitAmount)}・TTS ${sent.rate}）` : ""} {sent.status !== "SENT" && `— ${sent.screeningResult}`}
          </SuccessBox>
        )}
        <form className="wide" onSubmit={submit}>
          <div className="form-grid">
            <AccountPicker label="送金元口座（円または外貨）" value={form.accountNo} onChange={(v) => setForm({ ...form, accountNo: v })}
              filter={(a) => a.status === "ACTIVE" && a.accountType !== "TIME" && a.accountType !== "NOTICE"} />
            <AccountPicker label="手数料の引落口座（円）" value={form.feeAccountNo} onChange={(v) => setForm({ ...form, feeAccountNo: v })} />
            <label>
              通貨
              <select value={form.currency} onChange={set("currency")}><option>USD</option><option>EUR</option></select>
            </label>
            <label>
              金額
              <input inputMode="decimal" required maxLength={12} value={form.amount} onChange={set("amount")} placeholder="1000.00" />
            </label>
            <label>
              受取人名（英字）
              <input required maxLength={70} value={form.beneficiaryName} onChange={set("beneficiaryName")} placeholder="JOHN SMITH" />
            </label>
            <label>
              受取人口座（IBAN・口座番号）
              <input required maxLength={34} value={form.beneficiaryAccount} onChange={set("beneficiaryAccount")} placeholder="GB33BUKB20201555555555" />
            </label>
            <label>
              受取銀行 SWIFT/BIC
              <input required maxLength={11} value={form.swiftBic} onChange={set("swiftBic")} placeholder="BUKBGB22" />
            </label>
            <label>
              受取国（2文字）
              <input required maxLength={2} value={form.country} onChange={set("country")} placeholder="GB" />
            </label>
            <label>
              送金目的（英字）
              <input required maxLength={70} value={form.purpose} onChange={set("purpose")} placeholder="TUITION FEE" />
            </label>
          </div>
          <div className="actions">
            <button className="btn" type="submit" disabled={busy}>{busy ? "処理中…" : "送金する"}</button>
          </div>
        </form>
      </section>

      <section className="card table-wrap">
        <h2>海外送金の履歴</h2>
        <ErrorBox error={error} />
        {list && list.length === 0 && <p className="empty">履歴はありません。</p>}
        {list && list.length > 0 && (
          <table>
            <thead><tr><th>No.</th><th>受付</th><th>送金元</th><th className="num">金額</th><th>受取人</th><th>国</th><th>状態</th><th>照合結果</th><th /></tr></thead>
            <tbody>
              {list.map((r) => (
                <tr key={r.remitId}>
                  <td>{r.remitId}</td>
                  <td className="muted">{formatDateTime(r.createdAt)}</td>
                  <td>{r.accountNo}</td>
                  <td className="num">{formatMoney(r.amountMinor, r.currency)}</td>
                  <td>{r.beneficiaryName}</td>
                  <td>{r.country}</td>
                  <td><StatusBadge status={r.status} /></td>
                  <td style={{ whiteSpace: "normal", minWidth: 200 }} className="muted">{r.screeningResult}</td>
                  <td>
                    <div className="actions">
                      {r.status === "SENT" && (
                        <button className="btn btn-secondary btn-small" type="button"
                          onClick={() => showMessage(String(r.remitId))}>電文</button>
                      )}
                      {user?.role === "SUPERVISOR" && r.status === "HELD" && (
                        <>
                          <button className="btn btn-small" type="button" onClick={() => decide(String(r.remitId), "RELEASE")}>送金</button>
                          <button className="btn btn-danger btn-small" type="button" onClick={() => decide(String(r.remitId), "REJECT")}>謝絶</button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {/* 選択した送金の SWIFT MT103 電文（送信済みのもののみ） */}
        {message && (
          <div style={{ marginTop: 16 }}>
            <h3>SWIFT MT103（No.{message.remitId}）</h3>
            <pre className="mono" style={{ whiteSpace: "pre-wrap", background: "var(--surface-2)", padding: 12, borderRadius: 10 }}>
              {message.message}
            </pre>
          </div>
        )}
      </section>
    </>
  );
}

// ---------------------------------------------------------------- Zengin / incoming (supervisor)
/** 全銀データの作成・内容表示・決済完了（仕向）と、他行からの振込の入金処理・保留案件の処理（被仕向）。役席のみ。 */
function ZenginAndIncoming() {
  const { data: batches } = useZenginBatches();
  const { data: held } = useRemittances("IN", "HELD");
  const [err, setErr] = useState<unknown>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [content, setContent] = useState<Row | null>(null);
  const [incoming, setIncoming] = useState("");
  const [creditTo, setCreditTo] = useState<Record<string, string>>({});

  const run = async (fn: () => Promise<any>, done: (r: any) => string) => {
    setErr(null);
    setMessage(null);
    try {
      setMessage(done(await fn()));
    } catch (e2) {
      setErr(e2);
    }
  };

  // 被仕向の電文の代わりに、貼り付けた CSV テキストを入金データとして送る（仕向銀行コード＋照会番号で二重入金を防止）
  const postIncoming = () => {
    // 1行: 仕向銀行コード,振込依頼人名,口座番号,受取人名（カナ）,金額,照会番号
    const items = incoming.split(/\r?\n/).map((l) => l.trim()).filter(Boolean).map((l) => {
      const [bankCode, remitterNameKana, accountNo, nameKana, amount, referenceNo] = l.split(",").map((x) => x.trim());
      return { bankCode, remitterNameKana, accountNo, nameKana, amount, referenceNo };
    });
    return run(() => paymentApi.incoming(items),
      (r: Row[]) => `被仕向: ${r.map((x) => `${x.destAccountNo} ${remitStatusLabel[x.status]}${x.note ? `（${x.note}）` : ""}`).join("、")}`);
  };

  return (
    <>
      <section className="card">
        <h2>全銀データの作成（仕向）</h2>
        <p className="section-note">振込指定日が到来した送信待ちの振込を、全銀フォーマット（総合振込・120バイト固定長）にまとめて送信済みにします。</p>
        <ErrorBox error={err} />
        {message && <SuccessBox>{message}</SuccessBox>}
        <div className="actions" style={{ marginBottom: 12 }}>
          <button className="btn" type="button"
            onClick={() => run(paymentApi.createZengin, (b) => `全銀データ No.${b.batchId} を作成しました（${b.recordCount} 件・${formatYen(b.totalAmount)}）`)}>
            全銀データを作成
          </button>
        </div>
        {batches && batches.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>No.</th><th>作成</th><th>取組日</th><th className="num">件数</th><th className="num">合計</th><th /></tr></thead>
              <tbody>
                {batches.map((b) => (
                  <tr key={b.batchId}>
                    <td>{b.batchId}</td><td className="muted">{formatDateTime(b.createdAt)}</td><td>{formatDate(b.valueDate)}</td>
                    <td className="num">{b.recordCount}</td><td className="num">{formatYen(b.totalAmount)}</td>
                    <td>
                      <div className="actions">
                        <button className="btn btn-secondary btn-small" type="button"
                          onClick={async () => setContent(await paymentApi.zenginBatch(String(b.batchId)))}>内容</button>
                        <button className="btn btn-secondary btn-small" type="button"
                          onClick={() => run(() => paymentApi.settleZengin(String(b.batchId)), (r) => `決済完了を反映しました（${r.settled} 件）`)}>
                          決済完了
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {content && (
          <div style={{ marginTop: 16 }}>
            <h3>全銀データ No.{content.batchId}（120バイト × {String(content.content).split("\r\n").filter(Boolean).length} レコード）</h3>
            <pre className="mono" style={{ overflowX: "auto", background: "var(--surface-2)", padding: 12, borderRadius: 10, fontSize: "0.72rem" }}>
              {content.content}
            </pre>
          </div>
        )}
      </section>

      <section className="card">
        <h2>他行からの振込（被仕向）</h2>
        <p className="section-note">1行に「仕向銀行コード,振込依頼人名,口座番号,受取人名（カナ）,金額,照会番号」。口座番号と受取人名が一致すれば入金、不一致は保留します。仕向銀行コードと照会番号が同じ振込は二重に入金されません（重複として表示）。</p>
        <form onSubmit={(e) => { e.preventDefault(); void postIncoming(); }} style={{ maxWidth: "none" }}>
          <textarea rows={3} className="mono" value={incoming} onChange={(e) => setIncoming(e.target.value)}
            placeholder="0005,ｶ)ﾃｽﾄｼﾖｳｼﾞ,1000003,サトウ ハナコ,120000,ZG-20261120-0001" />
          <div className="actions"><button className="btn btn-small" type="submit">入金処理</button></div>
        </form>
        {/* 口座番号と受取人名が一致しなかった保留分：入金口座を指定して入金するか、仕向銀行へ返却する */}
        {held && held.length > 0 && (
          <div className="table-wrap" style={{ marginTop: 16 }}>
            <h3>保留中（{held.length} 件）</h3>
            <table>
              <thead><tr><th>No.</th><th>依頼人</th><th>指定口座</th><th>受取人名</th><th className="num">金額</th><th>理由</th><th>処理</th></tr></thead>
              <tbody>
                {held.map((r) => (
                  <tr key={r.remitId}>
                    <td>{r.remitId}</td><td>{r.remitterNameKana}</td><td>{r.destAccountNo}</td><td>{r.destNameKana}</td>
                    <td className="num">{formatYen(r.amount)}</td><td>{r.note}</td>
                    <td>
                      <div className="actions">
                        <input style={{ width: 100, minHeight: 34 }} maxLength={7} placeholder="入金口座"
                          value={creditTo[r.remitId] ?? r.destAccountNo} onChange={(e) => setCreditTo({ ...creditTo, [r.remitId]: e.target.value })} />
                        <button className="btn btn-small" type="button"
                          onClick={() => run(() => paymentApi.resolve(String(r.remitId), "CREDIT", creditTo[r.remitId] ?? r.destAccountNo), () => "入金しました")}>
                          入金
                        </button>
                        <button className="btn btn-secondary btn-small" type="button"
                          onClick={() => run(() => paymentApi.resolve(String(r.remitId), "RETURN"), () => "返却しました")}>
                          返却
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}

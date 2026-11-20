"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { RequireRole, useAuth } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { ApiError, bankApi, newIdempotencyKey, prefetchAccountDetail, useCustomer } from "@/lib/api";
import { riskApi, useCustomer360, type Row } from "@/lib/banking";
import {
  accountTypeLabel,
  formatDate,
  formatDateTime,
  formatMoney,
  formatYen,
  productLabel,
  loanStatusLabel,
  amlRuleLabel,
  idDocLabel,
  kycLabel,
  normalizeAmount,
  statusLabel,
} from "@/lib/format";
import type { Account, AccountType, Customer } from "@/lib/types";

export default function CustomerDetailPage() {
  return (
    <RequireRole roles={["TELLER", "SUPERVISOR"]}>
      <CustomerDetail />
    </RequireRole>
  );
}

function CustomerDetail() {
  const { user } = useAuth();
  const { customerId } = useParams<{ customerId: string }>();
  const { data: customer, error } = useCustomer(customerId);

  if (!customer) {
    if (!error) return <Loading />;
    return (
      <>
        <ErrorBox error={error} />
        <Link href="/customers">顧客一覧へ戻る</Link>
      </>
    );
  }

  return (
    <>
      <Link href="/customers" className="back-link">← 顧客一覧</Link>
      <h1>
        {customer.name} <span className="muted">（{customer.customerId}）</span>
      </h1>
      <ErrorBox error={error} />

      <section className="card">
        <dl className="summary">
          <div>
            <dt>フリガナ</dt>
            <dd>{customer.nameKana ?? "—"}</dd>
          </div>
          <div>
            <dt>生年月日</dt>
            <dd>{formatDate(customer.birthDate)}</dd>
          </div>
          <div>
            <dt>住所</dt>
            <dd>
              {customer.postalCode ? `〒${customer.postalCode} ` : ""}
              {customer.address ?? "—"}
            </dd>
          </div>
          <div>
            <dt>電話番号</dt>
            <dd>{customer.phone ?? "—"}</dd>
          </div>
          <div>
            <dt>本人確認書類</dt>
            <dd>{customer.idDocType ? `${idDocLabel[customer.idDocType]}（下4桁 ${customer.idDocLast4}）` : "—"}</dd>
          </div>
          <div>
            <dt>受付者／判定者</dt>
            <dd>
              {customer.registeredBy ?? "—"} ／ {customer.kycDecidedBy ?? "—"}
            </dd>
          </div>
          <div>
            <dt>本人確認</dt>
            <dd>
              <span className={`badge badge-${customer.kycStatus}`}>{kycLabel[customer.kycStatus]}</span>
              {customer.kycDecidedAt && <span className="muted"> {formatDateTime(customer.kycDecidedAt)}</span>}
            </dd>
          </div>
        </dl>
        {customer.kycNote && <p className="section-note" style={{ marginTop: 12 }}>備考: {customer.kycNote}</p>}
      </section>

      {customer.kycStatus === "PENDING" &&
        (user?.role === "SUPERVISOR" ? (
          <KycDecision customer={customer} />
        ) : (
          <div className="alert alert-error">本人確認待ちです。役席者が書類を確認して判定します（申込の受付者とは別の役席者）。</div>
        ))}
      {customer.kycStatus === "VERIFIED" && <OpenAccountForm customer={customer} />}
      {customer.kycStatus === "VERIFIED" && <IssueLogin customer={customer} />}
      {customer.kycStatus === "REJECTED" && (
        <div className="alert alert-error">本人確認が謝絶されているため、口座を開設できません。</div>
      )}

      <AccountList accounts={customer.accounts} />
      <Customer360 customerId={customer.customerId} />
    </>
  );
}

function KycDecision({ customer }: { customer: Customer }) {
  const [note, setNote] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const decide = async (decision: "VERIFIED" | "REJECTED") => {
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await bankApi.decideKyc({ customerId: customer.customerId, decision, note: note.trim() || undefined });
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section className="card">
      <h2>本人確認</h2>
      <p className="section-note">
        提示された{customer.idDocType ? idDocLabel[customer.idDocType] : "本人確認書類"}
        の原本で、氏名・生年月日・住所が申込内容と一致し、有効期限内であることを確認してください。
      </p>
      <ErrorBox error={error} />
      <form onSubmit={(e) => { e.preventDefault(); void decide("VERIFIED"); }}>
        <label>
          備考（謝絶の場合は理由が必須）
          <textarea rows={2} maxLength={200} value={note} onChange={(e) => setNote(e.target.value)}
            placeholder="例: 運転免許証の原本を確認" />
        </label>
        <div className="actions">
          <button className="btn" type="submit" disabled={submitting}>確認済にする</button>
          <button className="btn btn-danger" type="button" disabled={submitting} onClick={() => decide("REJECTED")}>
            謝絶する
          </button>
        </div>
      </form>
    </section>
  );
}

/** オンラインバンキングのログイン発行。初期パスワードはこの画面で一度だけ表示する（保存はハッシュのみ）。 */
function IssueLogin({ customer }: { customer: Customer }) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [issued, setIssued] = useState<{ userId: string; initialPassword: string } | null>(null);

  const issue = async () => {
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      setIssued(await bankApi.issueCustomerLogin(customer.customerId));
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section className="card">
      <h2>オンラインバンキング</h2>
      <ErrorBox error={error} />
      {issued ? (
        <SuccessBox>
          利用者ID <span className="secret">{issued.userId}</span> ／ 初期パスワード{" "}
          <span className="secret">{issued.initialPassword}</span>
          <br />
          この初期パスワードは再表示できません。お客さまに安全な方法でお渡しください（初回ログイン時に変更が必要です）。
        </SuccessBox>
      ) : (
        <div className="actions">
          <button className="btn btn-secondary" type="button" onClick={issue} disabled={submitting}>
            {submitting ? "発行中…" : "ログインを発行する"}
          </button>
        </div>
      )}
    </section>
  );
}

function OpenAccountForm({ customer }: { customer: Customer }) {
  const [accountType, setAccountType] = useState<AccountType>("ORDINARY");
  const [deposit, setDeposit] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [opened, setOpened] = useState<Account | null>(null);

  const held = new Set(customer.accounts.filter((a) => a.status !== "CLOSED").map((a) => a.accountType));
  const [currency, setCurrency] = useState("USD");
  // One account per type (per currency for foreign deposits); checking accounts for corporate customers only
  const heldForeign = new Set(customer.accounts.filter((a) => a.status !== "CLOSED" && a.accountType === "FOREIGN").map((a) => a.currency));
  const available = (["ORDINARY", "SAVINGS", "CHECKING", "NOTICE", "FOREIGN"] as const).filter((t) =>
    t === "FOREIGN" ? heldForeign.size < 2 : !held.has(t) && (t !== "CHECKING" || customer.customerType === "CORPORATE"));
  // After opening one type, fall back to the type that is still available
  const selected: AccountType = available.includes(accountType as (typeof available)[number])
    ? accountType
    : (available[0] ?? accountType);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    setOpened(null);
    try {
      const account = await bankApi.openAccount({
        customerId: customer.customerId,
        accountType: selected,
        currency: selected === "FOREIGN" ? currency : undefined,
        initialDeposit: selected === "FOREIGN" ? "0" : normalizeAmount(deposit) || "0",
        idempotencyKey: key,
      });
      setOpened(account);
      setDeposit("");
      setKey(newIdempotencyKey());
    } catch (err) {
      setError(err);
      if (err instanceof ApiError && err.code === "DUPLICATE_REQUEST") setKey(newIdempotencyKey());
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section className="card">
      <h2>口座開設</h2>
      <ErrorBox error={error} />
      {opened && (
        <SuccessBox>
          口座を開設しました：<Link href={`/accounts/${opened.accountNo}`}>{opened.accountNo}</Link>（
          {accountTypeLabel[opened.accountType]}、残高 {formatYen(opened.balance)}）
        </SuccessBox>
      )}
      {available.length === 0 ? (
        <p className="empty">普通・貯蓄の口座をすでにお持ちです（同じ種別はお一人さま1口座まで）。</p>
      ) : (
        <form onSubmit={submit}>
          <label>
            口座種別
            <select value={selected}
              onChange={(e) => setAccountType(e.target.value as AccountType)}>
              {available.map((t) => (
                <option key={t} value={t}>
                  {accountTypeLabel[t]}預金
                </option>
              ))}
            </select>
          </label>
          {selected === "FOREIGN" && (
            <label>
              通貨
              <select value={currency} onChange={(e) => setCurrency(e.target.value)}>
                {(["USD", "EUR"] as const).filter((c) => !heldForeign.has(c)).map((c) => <option key={c}>{c}</option>)}
              </select>
              <span className="hint">外貨預金は残高0で開設し、円口座からの両替で入金します</span>
            </label>
          )}
          <label>
            開設時入金額（円・任意）
            <input inputMode="numeric" autoComplete="off" maxLength={12} value={deposit}
              onChange={(e) => setDeposit(e.target.value)} placeholder="0" />
            <span className="hint">0円から開設できます（上限 10,000,000円）</span>
          </label>
          <div className="actions">
            <button className="btn" type="submit" disabled={submitting}>
              {submitting ? "処理中…" : "口座を開設する"}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}

function AccountList({ accounts }: { accounts: Account[] }) {
  return (
    <section className="card table-wrap">
      <h2>保有口座</h2>
      {accounts.length === 0 ? (
        <p className="empty">口座はありません。</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>口座番号</th>
              <th>種別</th>
              <th>状態</th>
              <th className="num">残高</th>
              <th>開設日</th>
              <th>最終異動日</th>
            </tr>
          </thead>
          <tbody>
            {accounts.map((a) => (
              <tr key={a.accountNo}>
                <td>
                  <Link href={`/accounts/${a.accountNo}`} onMouseEnter={() => prefetchAccountDetail(a.accountNo)}>
                    {a.accountNo}
                  </Link>
                </td>
                <td>{accountTypeLabel[a.accountType]}</td>
                <td>
                  <span className={`badge badge-${a.status}`}>{statusLabel[a.status]}</span>
                </td>
                <td className="num">{formatMoney(a.balance, a.currency)}</td>
                <td>{formatDate(a.openedAt)}</td>
                <td>{formatDate(a.lastActivityAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

const noteTypeLabel: Record<string, string> = { VISIT: "訪問", CALL: "電話", PROPOSAL: "提案", COMPLAINT: "苦情", MEMO: "メモ" };

/** 顧客 360°（CRM）: 預かり資産・融資・定期・口座振替・AML・接触履歴。 */
function Customer360({ customerId }: { customerId: string }) {
  const { data, error } = useCustomer360(customerId);
  const [type, setType] = useState("VISIT");
  const [content, setContent] = useState("");
  const [err, setErr] = useState<unknown>(null);
  if (!data) return error ? <ErrorBox error={error} /> : null;

  const addNote = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr(null);
    try {
      await riskApi.addNote(customerId, type, content);
      setContent("");
    } catch (e2) {
      setErr(e2);
    }
  };

  return (
    <section className="card">
      <h2>顧客 360°</h2>
      <dl className="stats">
        <div className="stat"><dt>顧客区分</dt><dd>{data.segment}</dd></div>
        <div className="stat"><dt>預かり資産（円換算）</dt><dd>{formatYen(data.totalAssets)}</dd></div>
        <div className="stat"><dt>融資残高</dt><dd>{formatYen(data.totalLoans)}</dd></div>
        <div className="stat"><dt>未対応の AML アラート</dt><dd className={data.amlAlerts.some((a: Row) => a.status === "OPEN") ? "minus" : ""}>
          {data.amlAlerts.filter((a: Row) => a.status === "OPEN").length} 件</dd></div>
      </dl>
      <div className="form-grid" style={{ gap: 20 }}>
        <div>
          <h3>融資</h3>
          {data.loans.length === 0 && data.applications.length === 0 ? <p className="empty">なし</p> : (
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {data.loans.map((l: Row) => (
                <li key={`l${l.loanId}`}><Link href={`/loans/${l.loanId}`}>{productLabel[l.product]} No.{l.loanId}</Link> 残高 {formatYen(l.outstanding)}（{loanStatusLabel[l.status]}）</li>
              ))}
              {data.applications.filter((a: Row) => !["EXECUTED", "REJECTED"].includes(a.status)).map((a: Row) => (
                <li key={`a${a.applicationId}`}><Link href={`/loans/applications/${a.applicationId}`}>申込 No.{a.applicationId}</Link> {productLabel[a.product]}（{loanStatusLabel[a.status]}）</li>
              ))}
            </ul>
          )}
        </div>
        <div>
          <h3>定期預金</h3>
          {data.timeDeposits.length === 0 ? <p className="empty">なし</p> : (
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {data.timeDeposits.map((t: Row) => (
                <li key={t.accountNo}><Link href={`/accounts/${t.accountNo}`}>{t.accountNo}</Link> {formatYen(t.principal)}・満期 {formatDate(t.maturityDate)}（{t.status === "ACTIVE" ? "有効" : t.status}）</li>
              ))}
            </ul>
          )}
        </div>
        <div>
          <h3>口座振替・AML</h3>
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            {data.mandates.map((m: Row) => <li key={`m${m.mandateId}`}>{m.billerName}（{m.status === "ACTIVE" ? "有効" : "解約"}）</li>)}
            {data.amlAlerts.map((a: Row) => <li key={`x${a.alertId}`} className="minus">{amlRuleLabel[a.ruleCode] ?? a.ruleCode}（{a.status}）</li>)}
            {data.mandates.length === 0 && data.amlAlerts.length === 0 && <li className="muted">なし</li>}
          </ul>
        </div>
      </div>

      <h3 style={{ marginTop: 20 }}>接触履歴</h3>
      <ErrorBox error={err} />
      <form className="filters" onSubmit={addNote}>
        <label>
          種類
          <select value={type} onChange={(e) => setType(e.target.value)}>
            {Object.entries(noteTypeLabel).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
        <label style={{ flex: "1 1 320px" }}>
          内容
          <input required maxLength={1000} value={content} onChange={(e) => setContent(e.target.value)} />
        </label>
        <div className="actions"><button className="btn btn-small" type="submit">記録</button></div>
      </form>
      {data.notes.length > 0 && (
        <div className="table-wrap">
          <table>
            <tbody>
              {data.notes.map((n: Row) => (
                <tr key={n.noteId}>
                  <td className="muted">{formatDateTime(n.createdAt)}</td><td>{noteTypeLabel[n.noteType]}</td>
                  <td style={{ whiteSpace: "normal" }}>{n.content}</td><td className="muted">{n.createdBy}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

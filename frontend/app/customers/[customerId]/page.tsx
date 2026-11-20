"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { ApiError, bankApi, newIdempotencyKey, prefetchAccountDetail, useCustomer } from "@/lib/api";
import {
  accountTypeLabel,
  formatDate,
  formatDateTime,
  formatYen,
  idDocLabel,
  kycLabel,
  normalizeAmount,
  statusLabel,
} from "@/lib/format";
import type { Account, AccountType, Customer } from "@/lib/types";

export default function CustomerDetailPage() {
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
      <p>
        <Link href="/customers">← 顧客一覧</Link>
      </p>
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
            <dt>本人確認</dt>
            <dd>
              <span className={`badge badge-${customer.kycStatus}`}>{kycLabel[customer.kycStatus]}</span>
              {customer.kycDecidedAt && <span className="muted"> {formatDateTime(customer.kycDecidedAt)}</span>}
            </dd>
          </div>
        </dl>
        {customer.kycNote && <p className="section-note" style={{ marginTop: 12 }}>備考: {customer.kycNote}</p>}
      </section>

      {customer.kycStatus === "PENDING" && <KycDecision customer={customer} />}
      {customer.kycStatus === "VERIFIED" && <OpenAccountForm customer={customer} />}
      {customer.kycStatus === "REJECTED" && (
        <div className="alert alert-error">本人確認が謝絶されているため、口座を開設できません。</div>
      )}

      <AccountList accounts={customer.accounts} />
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

function OpenAccountForm({ customer }: { customer: Customer }) {
  const [accountType, setAccountType] = useState<AccountType>("ORDINARY");
  const [deposit, setDeposit] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [opened, setOpened] = useState<Account | null>(null);

  const held = new Set(customer.accounts.filter((a) => a.status !== "CLOSED").map((a) => a.accountType));
  const available = (["ORDINARY", "SAVINGS"] as const).filter((t) => !held.has(t));
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
        initialDeposit: normalizeAmount(deposit) || "0",
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
                <td className="num">{formatYen(a.balance)}</td>
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

"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { ApiError, bankApi, newIdempotencyKey, useAccountDetail } from "@/lib/api";
import { CloseAccount, ReactivateAccount } from "@/components/AccountLifecycle";
import { InterestPreview } from "@/components/InterestPreview";
import { TransactionHistory } from "@/components/TransactionHistory";
import { accountTypeLabel, formatDate, formatYen, normalizeAmount, statusLabel } from "@/lib/format";
import type { OperationResult } from "@/lib/types";

type Mode = "deposit" | "withdraw";

export default function AccountDetailPage() {
  const { accountNo } = useParams<{ accountNo: string }>();
  // Cached (and possibly prefetched from the list page); refreshed automatically after deposit / withdraw
  const { data: detail, error: loadError } = useAccountDetail(accountNo);

  if (!detail) {
    if (!loadError) return <Loading />;
    return (
      <>
        <ErrorBox error={loadError} />
        <Link href="/">口座一覧へ戻る</Link>
      </>
    );
  }

  const { account } = detail;
  return (
    <>
      <p>
        <Link href="/">← 口座一覧</Link>
      </p>
      <h1>口座詳細 {account.accountNo}</h1>
      {/* A failed background refresh keeps the last data on screen and reports the problem */}
      <ErrorBox error={loadError} />

      <section className="card">
        <dl className="summary">
          <div>
            <dt>名義</dt>
            <dd>
              <Link href={`/customers/${account.customerId}`}>{account.customerName}</Link>
              <span className="muted">（{account.customerId}）</span>
            </dd>
          </div>
          <div>
            <dt>種別</dt>
            <dd>{accountTypeLabel[account.accountType]}</dd>
          </div>
          <div>
            <dt>状態</dt>
            <dd>
              <span className={`badge badge-${account.status}`}>{statusLabel[account.status]}</span>
            </dd>
          </div>
          <div>
            <dt>残高</dt>
            <dd className="balance">{formatYen(account.balance)}</dd>
          </div>
          <div>
            <dt>開設日</dt>
            <dd>{formatDate(account.openedAt)}</dd>
          </div>
          <div>
            <dt>最終異動日</dt>
            <dd>{formatDate(account.lastActivityAt)}</dd>
          </div>
          {account.dormancyNotifiedAt && (
            <div>
              <dt>休眠前通知</dt>
              <dd>{formatDate(account.dormancyNotifiedAt)}</dd>
            </div>
          )}
          {account.closedAt && (
            <div>
              <dt>解約日</dt>
              <dd>{formatDate(account.closedAt)}</dd>
            </div>
          )}
        </dl>
      </section>

      {account.status === "ACTIVE" && <CashForm accountNo={account.accountNo} />}
      {account.status === "FROZEN" && (
        <div className="alert alert-error">この口座は凍結中のため、お取引できません。</div>
      )}
      {account.status === "DORMANT" && <ReactivateAccount account={account} />}

      {account.status !== "CLOSED" && <InterestPreview accountNo={account.accountNo} />}
      <TransactionHistory accountNo={account.accountNo} />
      {(account.status === "ACTIVE" || account.status === "CLOSED") && <CloseAccount account={account} />}
    </>
  );
}

function CashForm({ accountNo }: { accountNo: string }) {
  const [mode, setMode] = useState<Mode>("deposit");
  const [amount, setAmount] = useState("");
  const [memo, setMemo] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<OperationResult | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    setResult(null);
    const req = { accountNo, amount: normalizeAmount(amount), memo: memo || undefined, idempotencyKey: key };
    try {
      const r = mode === "deposit" ? await bankApi.deposit(req) : await bankApi.withdraw(req);
      setResult(r);
      setAmount("");
      setMemo("");
      setKey(newIdempotencyKey()); // next operation gets a new key (the balance / history refresh by itself)
    } catch (err) {
      setError(err);
      // A duplicate means the earlier attempt went through: start a fresh operation
      if (err instanceof ApiError && err.code === "DUPLICATE_REQUEST") setKey(newIdempotencyKey());
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section className="card">
      <div className="tabs" role="tablist">
        {(["deposit", "withdraw"] as const).map((m) => (
          <button
            key={m}
            type="button"
            role="tab"
            className="tab"
            aria-selected={mode === m}
            onClick={() => {
              setMode(m);
              setError(null);
              setResult(null);
            }}
          >
            {m === "deposit" ? "入金" : "出金"}
          </button>
        ))}
      </div>

      <ErrorBox error={error} />
      {result && (
        <SuccessBox>
          {result.operation === "DEPOSIT" ? "入金" : "出金"}しました：{formatYen(result.amount)}（取引後残高{" "}
          {formatYen(result.balanceAfter)}）
        </SuccessBox>
      )}

      <form onSubmit={submit}>
        <label>
          金額（円）
          <input
            inputMode="numeric"
            autoComplete="off"
            required
            maxLength={12}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            placeholder="10000"
          />
          <span className="hint">1円以上・1回あたり10,000,000円まで</span>
        </label>
        <label>
          メモ（任意）
          <input maxLength={50} value={memo} onChange={(e) => setMemo(e.target.value)} />
        </label>
        <div className="actions">
          <button className="btn" type="submit" disabled={submitting}>
            {submitting ? "処理中…" : mode === "deposit" ? "入金する" : "出金する"}
          </button>
        </div>
      </form>
    </section>
  );
}

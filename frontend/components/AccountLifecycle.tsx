"use client";

import { useState } from "react";
import { ErrorBox, SuccessBox } from "@/components/Feedback";
import { InterestTable } from "@/components/InterestPreview";
import { ApiError, bankApi, newIdempotencyKey, useCustomer, useInterestPreview } from "@/lib/api";
import { accountTypeLabel, formatDateTime, formatYen, todayIso } from "@/lib/format";
import type { Account, CloseResult } from "@/lib/types";

/** 口座解約: 解約日前日までの利息を決算し、全額を本人名義の口座または現金で払い戻す。 */
export function CloseAccount({ account }: { account: Account }) {
  const { data: customer } = useCustomer(account.customerId);
  const { data: interest } = useInterestPreview(account.accountNo, todayIso());
  const [payout, setPayout] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [key, setKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<CloseResult | null>(null);

  const payoutCandidates =
    customer?.accounts.filter((a) => a.status === "ACTIVE" && a.accountNo !== account.accountNo) ?? [];

  const execute = async () => {
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      setResult(
        await bankApi.closeAccount({
          accountNo: account.accountNo,
          payoutAccountNo: payout || undefined,
          idempotencyKey: key,
        }),
      );
    } catch (err) {
      setError(err);
      setConfirming(false);
      if (err instanceof ApiError && err.code === "DUPLICATE_REQUEST") setKey(newIdempotencyKey());
    } finally {
      setSubmitting(false);
    }
  };

  if (result) {
    return (
      <section className="card confirm">
        <SuccessBox>口座 {result.accountNo} を解約しました。</SuccessBox>
        <dl>
          <dt>解約日時</dt>
          <dd>{formatDateTime(result.closedAt)}</dd>
          <dt>決算利息（税引後）</dt>
          <dd>{formatYen(result.interest.netInterest)}</dd>
          <dt>払戻し額</dt>
          <dd>{formatYen(result.payoutAmount)}</dd>
          <dt>払戻し方法</dt>
          <dd>{result.payoutAccountNo ? `口座 ${result.payoutAccountNo} へ入金` : "現金"}</dd>
        </dl>
      </section>
    );
  }

  if (account.status === "CLOSED") {
    return (
      <section className="card">
        <h2>口座解約</h2>
        <p className="empty">この口座は {formatDateTime(account.closedAt)} に解約済みです。</p>
      </section>
    );
  }

  const estimatedPayout = account.balance + (interest?.netInterest ?? 0);
  return (
    <section className="card">
      <h2>口座解約</h2>
      <p className="section-note">解約日前日までの利息を決算したうえで、残高をすべて払い戻します。この操作は取り消せません。</p>
      <ErrorBox error={error} />
      {!confirming ? (
        <form onSubmit={(e) => { e.preventDefault(); setError(null); setConfirming(true); }}>
          <label>
            払戻し方法
            <select value={payout} onChange={(e) => setPayout(e.target.value)}>
              <option value="">現金で払い戻す</option>
              {payoutCandidates.map((a) => (
                <option key={a.accountNo} value={a.accountNo}>
                  本人名義の口座 {a.accountNo}（{accountTypeLabel[a.accountType]}）へ入金
                </option>
              ))}
            </select>
          </label>
          <div className="actions">
            <button className="btn btn-secondary" type="submit">解約内容を確認</button>
          </div>
        </form>
      ) : (
        <div className="confirm">
          <dl>
            <dt>解約口座</dt>
            <dd>{account.accountNo}（{accountTypeLabel[account.accountType]}）</dd>
            <dt>現在残高</dt>
            <dd>{formatYen(account.balance)}</dd>
            <dt>払戻し方法</dt>
            <dd>{payout ? `口座 ${payout} へ入金` : "現金"}</dd>
            <dt>払戻し見込み額</dt>
            <dd>{formatYen(estimatedPayout)}</dd>
          </dl>
          {interest && <InterestTable result={interest} />}
          <div className="actions">
            <button className="btn btn-danger" type="button" onClick={execute} disabled={submitting}>
              {submitting ? "処理中…" : "解約を実行する"}
            </button>
            <button className="btn btn-secondary" type="button" onClick={() => setConfirming(false)} disabled={submitting}>
              戻る
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

/** 休眠解除（本人確認済みの顧客からの申出）。 */
export function ReactivateAccount({ account }: { account: Account }) {
  const [key, setKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState(false);

  const execute = async () => {
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await bankApi.reactivateAccount({ accountNo: account.accountNo, idempotencyKey: key });
      setDone(true);
    } catch (err) {
      setError(err);
      if (err instanceof ApiError && err.code === "DUPLICATE_REQUEST") setKey(newIdempotencyKey());
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section className="card">
      <h2>休眠口座</h2>
      <p className="section-note">
        最終異動日 {formatDateTime(account.lastActivityAt)}、休眠日 {formatDateTime(account.dormantAt)}。
        お取引を再開するには、ご本人の申出と本人確認のうえで休眠を解除してください。
      </p>
      <ErrorBox error={error} />
      {done && <SuccessBox>休眠を解除しました。お取引が可能です。</SuccessBox>}
      {!done && (
        <div className="actions">
          <button className="btn" type="button" onClick={execute} disabled={submitting}>
            {submitting ? "処理中…" : "本人確認済み・休眠を解除する"}
          </button>
        </div>
      )}
    </section>
  );
}

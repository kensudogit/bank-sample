"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useAuth } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { ApiError, bankApi, newIdempotencyKey, prefetchAccountDetail, useAccounts } from "@/lib/api";
import { formatDateTime, formatYen, normalizeAmount } from "@/lib/format";
import type { OperationResult } from "@/lib/types";

type Step = "input" | "confirm" | "done";
/** 振込: 任意の口座へ / 振替: 同じ名義の口座間 */
type Kind = "transfer" | "own";

export default function TransferPage() {
  const { user } = useAuth();
  // Customers: only their own accounts come back. Staff: used for input suggestions only.
  const { data: accountPage, error: loadError } = useAccounts({ size: 200 });
  const accounts = accountPage?.items;
  const isCustomer = user?.customer ?? false;
  const [step, setStep] = useState<Step>("input");
  const [kind, setKind] = useState<Kind>("transfer");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [amount, setAmount] = useState("");
  const [memo, setMemo] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<OperationResult | null>(null);

  // Default the source account once the list is known (customers choose from their own accounts)
  useEffect(() => {
    if (from || !accounts || !isCustomer) return;
    const firstActive = accounts.find((a) => a.status === "ACTIVE");
    if (firstActive) setFrom(firstActive.accountNo);
  }, [accounts, from, isCustomer]);

  if (!accounts) return loadError ? <ErrorBox error={loadError} /> : <Loading />;

  const active = accounts.filter((a) => a.status === "ACTIVE");
  const fromAccount = accounts.find((a) => a.accountNo === from);
  const toAccount = accounts.find((a) => a.accountNo === to);
  const normalized = normalizeAmount(amount);
  const label = kind === "own" ? "振替" : "振込";
  // 振替先: 振込元と同じ名義の利用可能な口座
  const ownTargets = active.filter((a) => fromAccount && a.customerId === fromAccount.customerId && a.accountNo !== from);

  const toConfirm = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!/^\d{7}$/.test(from)) return setError(new ApiError(400, "VALIDATION_ERROR", `${label}元口座番号は7桁の数字で入力してください。`));
    if (!/^\d{7}$/.test(to)) return setError(new ApiError(400, "VALIDATION_ERROR", `${label}先口座番号は7桁の数字で入力してください。`));
    if (from === to) return setError(new ApiError(422, "SAME_ACCOUNT", `${label}元と${label}先に同じ口座は指定できません。`));
    if (!/^[1-9]\d{0,8}$/.test(normalized)) return setError(new ApiError(400, "VALIDATION_ERROR", "金額は1円以上の整数で入力してください。"));
    setStep("confirm");
  };

  const execute = async () => {
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const r = await bankApi.transfer({
        fromAccountNo: from,
        toAccountNo: to,
        amount: normalized,
        memo: memo || undefined,
        idempotencyKey: key,
        ownAccountsOnly: kind === "own",
      });
      setResult(r);
      setStep("done");
    } catch (err) {
      setError(err);
      if (err instanceof ApiError && err.code === "DUPLICATE_REQUEST") setKey(newIdempotencyKey());
      setStep("input");
    } finally {
      setSubmitting(false);
    }
  };

  const reset = () => {
    setTo("");
    setAmount("");
    setMemo("");
    setResult(null);
    setKey(newIdempotencyKey());
    setStep("input");
  };

  return (
    <>
      <h1>振込・振替</h1>
      <ErrorBox error={error} />

      {step === "input" && (
        <section className="card">
          <div className="tabs" role="tablist">
            {(["transfer", "own"] as const).map((k) => (
              <button key={k} type="button" role="tab" className="tab" aria-selected={kind === k}
                onClick={() => { setKind(k); setTo(""); setError(null); }}>
                {k === "transfer" ? "振込（他の口座へ）" : "振替（ご本人の口座間）"}
              </button>
            ))}
          </div>
          <form onSubmit={toConfirm}>
            <label>
              {label}元口座
              {isCustomer ? (
                <select value={from} onChange={(e) => { setFrom(e.target.value); if (kind === "own") setTo(""); }} required>
                  {active.map((a) => (
                    <option key={a.accountNo} value={a.accountNo}>
                      {a.accountNo}（残高 {formatYen(a.balance)}）
                    </option>
                  ))}
                </select>
              ) : (
                <input inputMode="numeric" autoComplete="off" required maxLength={7} value={from}
                  onChange={(e) => setFrom(normalizeAmount(e.target.value))} placeholder="1000001" />
              )}
            </label>
            {kind === "own" && isCustomer ? (
              <label>
                振替先口座
                <select value={to} onChange={(e) => setTo(e.target.value)} required>
                  <option value="">選択してください</option>
                  {ownTargets.map((a) => (
                    <option key={a.accountNo} value={a.accountNo}>
                      {a.accountNo}（残高 {formatYen(a.balance)}）
                    </option>
                  ))}
                </select>
                {ownTargets.length === 0 && <span className="hint">同じ名義の利用可能な口座がありません。</span>}
              </label>
            ) : (
            <label>
              {label}先口座番号
              <input
                inputMode="numeric"
                autoComplete="off"
                required
                maxLength={7}
                value={to}
                onChange={(e) => setTo(normalizeAmount(e.target.value))}
                placeholder="1000003"
              />
              <span className="hint">
                7桁の数字{kind === "own" ? "（振込元と同じ名義の口座のみ）" : ""}
              </span>
            </label>
            )}
            <label>
              金額（円）
              <input
                inputMode="numeric"
                autoComplete="off"
                required
                maxLength={12}
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                placeholder="5000"
              />
            </label>
            <label>
              メモ（任意）
              <input maxLength={50} value={memo} onChange={(e) => setMemo(e.target.value)} />
            </label>
            <div className="actions">
              <button className="btn" type="submit">
                確認へ進む
              </button>
            </div>
          </form>
        </section>
      )}

      {step === "confirm" && (
        <section className="card confirm">
          <h2>{label}内容の確認</h2>
          <dl>
            <dt>{label}元</dt>
            <dd>
              {from} {fromAccount?.customerName}
            </dd>
            <dt>{label}先</dt>
            <dd>
              {to} {toAccount?.customerName ?? ""}
            </dd>
            <dt>金額</dt>
            <dd>{formatYen(Number(normalized))}</dd>
            <dt>メモ</dt>
            <dd>{memo || "—"}</dd>
          </dl>
          <div className="actions">
            <button className="btn" type="button" onClick={execute} disabled={submitting}>
              {submitting ? "処理中…" : `${label}を実行する`}
            </button>
            <button className="btn btn-secondary" type="button" onClick={() => setStep("input")} disabled={submitting}>
              修正する
            </button>
          </div>
        </section>
      )}

      {step === "done" && result && (
        <section className="card confirm">
          <SuccessBox>{label}が完了しました。</SuccessBox>
          <dl>
            <dt>{label}元</dt>
            <dd>{result.accountNo}</dd>
            <dt>{label}先</dt>
            <dd>{result.counterpartyAccountNo}</dd>
            <dt>金額</dt>
            <dd>{formatYen(result.amount)}</dd>
            <dt>{label}元 取引後残高</dt>
            <dd>{formatYen(result.balanceAfter)}</dd>
            <dt>実行日時</dt>
            <dd>{formatDateTime(result.executedAt)}</dd>
          </dl>
          <div className="actions">
            <Link
              className="btn"
              href={`/accounts/${result.accountNo}`}
              onMouseEnter={() => prefetchAccountDetail(result.accountNo)}
              onFocus={() => prefetchAccountDetail(result.accountNo)}
              onTouchStart={() => prefetchAccountDetail(result.accountNo)}
            >
              {label}元の明細を見る
            </Link>
            <button className="btn btn-secondary" type="button" onClick={reset}>
              続けて{label}
            </button>
          </div>
        </section>
      )}
    </>
  );
}

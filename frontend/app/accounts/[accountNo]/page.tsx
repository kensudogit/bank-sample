"use client";

// 口座詳細の画面（/accounts/[accountNo]）。残高・名義などの概要と、利用者の役割と口座の状態に応じた
// 操作パネル（入出金・休眠解除・解約）、残高推移・利息見込み・取引履歴・取引の追跡を表示する。

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { ApiError, bankApi, newIdempotencyKey, useAccountDetail } from "@/lib/api";
import { CloseAccount, ReactivateAccount } from "@/components/AccountLifecycle";
import { BalanceChart } from "@/components/BalanceChart";
import { DepositPanels } from "@/components/DepositPanels";
import { IconArrowLeft, IconTransfer } from "@/components/Icons";
import { InterestPreview } from "@/components/InterestPreview";
import { LedgerTrace } from "@/components/LedgerTrace";
import { TransactionHistory } from "@/components/TransactionHistory";
import { accountTypeLabel, formatDate, formatMoney, formatYen, normalizeAmount, statusLabel } from "@/lib/format";
import type { OperationResult } from "@/lib/types";

/** 窓口の現金取引の種類 */
type Mode = "deposit" | "withdraw";

/** 口座詳細ページ。顧客は自分の口座のみ閲覧でき（サーバー側で確認）、行員は操作パネルも使える。 */
export default function AccountDetailPage() {
  const { accountNo } = useParams<{ accountNo: string }>();
  // Cached (and possibly prefetched from the list page); refreshed automatically after deposit / withdraw
  const { data: detail, error: loadError } = useAccountDetail(accountNo);
  const { user } = useAuth();
  const isStaff = user?.staff ?? false;
  const isSupervisor = user?.role === "SUPERVISOR";

  // まだデータが無い：読み込み中、または取得エラー（存在しない・権限がない口座など）
  if (!detail) {
    if (!loadError) return <Loading />;
    return (
      <>
        <ErrorBox error={loadError} />
        <Link href="/" className="back-link">
          <IconArrowLeft size={16} /> 口座一覧へ戻る
        </Link>
      </>
    );
  }

  const { account } = detail;
  return (
    <>
      <Link href="/" className="back-link">
        <IconArrowLeft size={16} /> {isStaff ? "口座一覧" : "ご利用口座"}
      </Link>
      {/* A failed background refresh keeps the last data on screen and reports the problem */}
      <ErrorBox error={loadError} />

      {/* 口座の概要（種別・番号・状態・残高・名義・各日付） */}
      <section className="hero" aria-label="口座の概要">
        <div className="tile-top" style={{ position: "relative" }}>
          <span className="hero-label">
            {accountTypeLabel[account.accountType]}預金{account.currency !== "JPY" ? `（${account.currency}）` : ""} ・ No.{" "}
            {account.accountNo}
          </span>
          <span className="chip">{statusLabel[account.status]}</span>
        </div>
        <div className="hero-amount">{formatMoney(account.balance, account.currency)}</div>
        <div className="hero-meta">
          <span>
            名義{" "}
            {isStaff ? (
              <Link href={`/customers/${account.customerId}`} style={{ color: "#fff", fontWeight: 700 }}>
                {account.customerName}
              </Link>
            ) : (
              <strong>{account.customerName}</strong>
            )}
          </span>
          <span>
            開設日 <strong>{formatDate(account.openedAt)}</strong>
          </span>
          <span>
            最終異動日 <strong>{formatDate(account.lastActivityAt)}</strong>
          </span>
          {account.dormancyNotifiedAt && (
            <span>
              休眠前通知 <strong>{formatDate(account.dormancyNotifiedAt)}</strong>
            </span>
          )}
          {account.closedAt && (
            <span>
              解約日 <strong>{formatDate(account.closedAt)}</strong>
            </span>
          )}
        </div>
        {account.status === "ACTIVE" && account.currency === "JPY" && account.accountType !== "TIME" && (
          <div className="hero-actions">
            <Link className="btn btn-small" href="/transfer">
              <IconTransfer size={16} /> 振込・振替
            </Link>
            <Link className="btn btn-small" href={`/accounts/${account.accountNo}/certificate`}>残高証明書</Link>
          </div>
        )}
        {!(account.status === "ACTIVE" && account.currency === "JPY" && account.accountType !== "TIME") && (
          <div className="hero-actions">
            <Link className="btn btn-small" href={`/accounts/${account.accountNo}/certificate`}>残高証明書</Link>
          </div>
        )}
      </section>

      {/* Cash deposit / withdrawal is a counter operation (staff only); customers use transfers */}
      <DepositPanels account={account} />
      {account.status === "ACTIVE" && isStaff && account.currency === "JPY" && account.accountType !== "TIME" && (
        <CashForm accountNo={account.accountNo} />
      )}
      {account.status === "FROZEN" && (
        <div className="alert alert-error">この口座は凍結中のため、お取引できません。</div>
      )}
      {/* 休眠口座：解除は管理者のみ。それ以外の利用者には窓口への案内を出す */}
      {account.status === "DORMANT" &&
        (isSupervisor ? (
          <ReactivateAccount account={account} />
        ) : (
          <div className="alert alert-error">この口座は休眠中です。お取引の再開は窓口でお申し出ください。</div>
        ))}

      <BalanceChart accountNo={account.accountNo} currentBalance={account.balance} currency={account.currency} />
      {/* 利息見込みは定期（満期時に別計算）と当座（無利息）以外で表示する */}
      {account.status !== "CLOSED" && account.accountType !== "TIME" && account.accountType !== "CHECKING" && (
        <InterestPreview accountNo={account.accountNo} currency={account.currency} />
      )}
      <TransactionHistory accountNo={account.accountNo} currency={account.currency} />
      {isStaff && <LedgerTrace accountNo={account.accountNo} currency={account.currency} />}
      {isSupervisor && account.accountType !== "TIME" && (account.status === "ACTIVE" || account.status === "CLOSED") && (
        <CloseAccount account={account} />
      )}
    </>
  );
}

/**
 * 窓口の現金入金・出金フォーム（行員のみ）。
 * 冪等キーにより、通信エラーで再送しても入出金が二重に記帳されない。
 */
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

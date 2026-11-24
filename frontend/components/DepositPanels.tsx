"use client";

// 口座詳細画面に載せる、預金種類ごとの専用パネル群：
// 定期預金（内容表示・中途解約）、通知預金（払出しの通知）、外貨預金（両替）、当座預金（当座貸越の利用状況）。

import { useState } from "react";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { ApiError, newIdempotencyKey, useCustomer } from "@/lib/api";
import { depositApi, useFxRates, useNotices, useTimeDeposit, type Row } from "@/lib/banking";
import { formatDate, formatDateTime, formatMoney, formatYen, normalizeAmount } from "@/lib/format";
import type { Account } from "@/lib/types";

/** Panels that depend on the deposit type (定期・通知・外貨・当座). */
// 口座種別に応じて表示するパネルを切り替える（普通・貯蓄には専用パネルなし）
export function DepositPanels({ account }: { account: Account }) {
  switch (account.accountType) {
    case "TIME":
      return <TimeDepositPanel account={account} />;
    case "NOTICE":
      return account.status === "ACTIVE" ? <NoticePanel account={account} /> : null;
    case "FOREIGN":
      return account.status === "ACTIVE" ? <FxPanel account={account} /> : null;
    case "CHECKING":
      return <OverdraftPanel account={account} />;
    default:
      return null;
  }
}

/** 定期預金の内容と中途解約。冪等キーはパネル表示中ずっと同じものを使い、二重解約を防ぐ。 */
function TimeDepositPanel({ account }: { account: Account }) {
  const { data: td, error } = useTimeDeposit(account.accountNo);
  const [confirming, setConfirming] = useState(false);
  const [key] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const [result, setResult] = useState<Row | null>(null);

  const cancel = async () => {
    setBusy(true);
    setErr(null);
    try {
      setResult(await depositApi.cancelTime(account.accountNo, key));
    } catch (e) {
      setErr(e);
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  };

  return (
    <section className="card">
      <h2>定期預金</h2>
      <ErrorBox error={error ?? err} />
      {!td && !error && <Loading />}
      {result && (
        <SuccessBox>
          中途解約しました：利息 {formatYen(result.grossInterest)}（税 {formatYen(result.nationalTax + result.localTax)}）、
          元利金 {formatYen(result.payout)} を口座 {result.payoutAccountNo} へ入金しました。
        </SuccessBox>
      )}
      {td && (
        <>
          <dl className="stats">
            <div className="stat"><dt>元本</dt><dd>{formatYen(td.principal)}</dd></div>
            <div className="stat"><dt>金利（年・固定）</dt><dd>{Number(td.annualRate).toFixed(3)}%</dd></div>
            <div className="stat"><dt>期間</dt><dd>{td.termMonths}か月</dd></div>
            <div className="stat"><dt>預入日</dt><dd>{formatDate(td.startDate)}</dd></div>
            <div className="stat"><dt>満期日</dt><dd>{formatDate(td.maturityDate)}</dd></div>
            <div className="stat">
              <dt>満期時の取扱い</dt>
              <dd>{td.maturityInstruction === "ROLLOVER" ? "元利継続（自動継続）" : `元利金を ${td.linkedAccountNo} へ`}</dd>
            </div>
            <div className="stat"><dt>満期時の受取利息（税引後・見込み）</dt><dd className="plus">{formatYen(td.expectedNetInterest)}</dd></div>
            <div className="stat"><dt>継続回数</dt><dd>{td.rolloverCount}</dd></div>
          </dl>
          {td.status === "ACTIVE" && account.status === "ACTIVE" && (
            <div className="actions">
              {!confirming ? (
                <button className="btn btn-secondary" type="button" onClick={() => setConfirming(true)}>中途解約…</button>
              ) : (
                <>
                  <span className="muted">中途解約すると普通預金の金利で計算します。よろしいですか？</span>
                  <button className="btn btn-danger" type="button" onClick={cancel} disabled={busy}>
                    {busy ? "処理中…" : "中途解約する"}
                  </button>
                  <button className="btn btn-secondary" type="button" onClick={() => setConfirming(false)}>やめる</button>
                </>
              )}
            </div>
          )}
        </>
      )}
    </section>
  );
}

/** 通知預金：払出予定額の通知と、通知の一覧・取消。 */
function NoticePanel({ account }: { account: Account }) {
  const { data: notices, error } = useNotices(account.accountNo);
  const [amount, setAmount] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const [created, setCreated] = useState<Row | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      setCreated(await depositApi.notify(account.accountNo, normalizeAmount(amount)));
      setAmount("");
    } catch (e2) {
      setErr(e2);
    } finally {
      setBusy(false);
    }
  };

  const cancel = async (noticeId: string) => {
    setErr(null);
    try {
      await depositApi.cancelNotice(account.accountNo, noticeId);
    } catch (e2) {
      setErr(e2);
    }
  };

  return (
    <section className="card">
      <h2>払出しの通知（通知預金）</h2>
      <p className="section-note">据置期間（預入から7日）経過後、払出しの2日前までに通知が必要です。通知した金額以内で、払出可能日以降に出金・振込できます。</p>
      <ErrorBox error={error ?? err} />
      {created && <SuccessBox>{formatYen(created.amount)} を通知しました。払出可能日: {created.availableFrom}</SuccessBox>}
      <form className="filters" onSubmit={submit}>
        <label>
          払出予定額（円）
          <input inputMode="numeric" required maxLength={12} value={amount} onChange={(e) => setAmount(e.target.value)} />
        </label>
        <div className="actions">
          <button className="btn" type="submit" disabled={busy}>{busy ? "処理中…" : "通知する"}</button>
        </div>
      </form>
      {notices && notices.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>通知日時</th><th className="num">金額</th><th>払出可能日</th><th>状態</th><th /></tr></thead>
            <tbody>
              {notices.map((n) => (
                <tr key={n.noticeId}>
                  <td className="muted">{formatDateTime(n.notifiedAt)}</td>
                  <td className="num">{formatYen(n.amount)}</td>
                  <td>{n.availableFrom}</td>
                  <td>{n.status === "OPEN" ? "有効" : n.status === "USED" ? "払出済" : "取消"}</td>
                  <td>
                    {n.status === "OPEN" && (
                      <button className="btn btn-secondary btn-small" type="button" onClick={() => cancel(String(n.noticeId))}>
                        取消
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
  );
}

/** 外貨預金の両替：円 → 外貨（TTS で購入）と、外貨 → 円（TTB で売却）。 */
function FxPanel({ account }: { account: Account }) {
  const { data: rates } = useFxRates();
  const { data: customer } = useCustomer(account.customerId);
  const rate = rates?.find((r) => r.currency === account.currency);
  // 両替の相手口座は、同じ顧客名義の利用可能な円口座（普通・貯蓄・当座）
  const yenAccounts = customer?.accounts.filter(
    (a) => a.status === "ACTIVE" && a.currency === "JPY" && ["ORDINARY", "SAVINGS", "CHECKING"].includes(a.accountType),
  ) ?? [];
  const [direction, setDirection] = useState<"BUY" | "SELL">("BUY");
  const [yenAccount, setYenAccount] = useState("");
  const [amount, setAmount] = useState("");
  const [key, setKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const [result, setResult] = useState<Row | null>(null);

  const selectedYen = yenAccount || yenAccounts[0]?.accountNo || "";
  const foreign = Number(amount || 0);
  const applied = rate ? (direction === "BUY" ? Number(rate.tts) : Number(rate.ttb)) : 0;
  // 円換算の概算：購入は銀行に有利なよう 1 円未満切上げ、売却は切捨て（サーバー側の計算と同じ丸め）
  const estimate = direction === "BUY" ? Math.ceil(foreign * applied) : Math.floor(foreign * applied);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    // 外貨は補助単位（セント）まで、小数点以下 2 桁までに制限する
    if (!/^\d{1,9}(\.\d{1,2})?$/.test(amount)) {
      setErr(new ApiError(400, "VALIDATION_ERROR", "金額は小数点以下2桁までの数値で入力してください。"));
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      setResult(await depositApi.exchange({
        fromAccountNo: direction === "BUY" ? selectedYen : account.accountNo,
        toAccountNo: direction === "BUY" ? account.accountNo : selectedYen,
        amount,
        idempotencyKey: key,
      }));
      setAmount("");
      setKey(newIdempotencyKey());
    } catch (e2) {
      setErr(e2);
      if (e2 instanceof ApiError && e2.code === "DUPLICATE_REQUEST") setKey(newIdempotencyKey());
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card">
      <h2>外貨の両替（{account.currency}）</h2>
      {rate && (
        <dl className="stats">
          <div className="stat"><dt>TTS（円→外貨）</dt><dd>{Number(rate.tts).toFixed(2)} 円</dd></div>
          <div className="stat"><dt>TTM（仲値）</dt><dd>{Number(rate.ttm).toFixed(2)} 円</dd></div>
          <div className="stat"><dt>TTB（外貨→円）</dt><dd>{Number(rate.ttb).toFixed(2)} 円</dd></div>
          <div className="stat"><dt>更新</dt><dd style={{ fontSize: "0.85rem" }}>{formatDateTime(rate.updatedAt)}</dd></div>
        </dl>
      )}
      <ErrorBox error={err} />
      {result && (
        <SuccessBox>
          {result.direction === "BUY" ? "外貨を購入" : "外貨を売却"}しました：{result.currency} {result.foreignAmount}（{formatYen(result.yenAmount)}・レート {result.rate}）
        </SuccessBox>
      )}
      <div className="tabs" role="tablist">
        {(["BUY", "SELL"] as const).map((d) => (
          <button key={d} type="button" role="tab" className="tab" aria-selected={direction === d} onClick={() => setDirection(d)}>
            {d === "BUY" ? "円 → 外貨（入金）" : "外貨 → 円（払出）"}
          </button>
        ))}
      </div>
      <form onSubmit={submit}>
        <label>
          {direction === "BUY" ? "円の支払口座" : "円の入金口座"}
          <select value={selectedYen} onChange={(e) => setYenAccount(e.target.value)} required>
            {yenAccounts.map((a) => (
              <option key={a.accountNo} value={a.accountNo}>{a.accountNo}（残高 {formatYen(a.balance)}）</option>
            ))}
          </select>
        </label>
        <label>
          金額（{account.currency}）
          <input inputMode="decimal" required maxLength={12} value={amount} onChange={(e) => setAmount(e.target.value)}
            placeholder="100.00" />
          {foreign > 0 && rate && (
            <span className="hint">
              概算 {formatYen(estimate)}（{direction === "BUY" ? "TTS・1円未満切上げ" : "TTB・1円未満切捨て"}）
              {direction === "SELL" && ` ／ 外貨残高 ${formatMoney(account.balance, account.currency)}`}
            </span>
          )}
        </label>
        <div className="actions">
          <button className="btn" type="submit" disabled={busy || !selectedYen}>{busy ? "処理中…" : "両替する"}</button>
        </div>
      </form>
    </section>
  );
}

/** 当座預金：当座貸越の極度額・利用額・支払可能額。残高がマイナスの分が貸越利用額。 */
function OverdraftPanel({ account }: { account: Account }) {
  const used = Math.max(0, -account.balance);
  return (
    <section className="card">
      <h2>当座預金</h2>
      <dl className="stats">
        <div className="stat"><dt>当座貸越 極度額</dt><dd>{formatYen(account.overdraftLimit)}</dd></div>
        <div className="stat"><dt>貸越利用額</dt><dd className={used > 0 ? "minus" : ""}>{formatYen(used)}</dd></div>
        <div className="stat"><dt>支払可能額</dt><dd>{formatYen(account.availableBalance)}</dd></div>
      </dl>
      <p className="section-note" style={{ margin: 0 }}>
        当座預金は無利息です。当座貸越は融資（当座貸越契約）で極度額を設定し、貸越残高に利息がかかります。
      </p>
    </section>
  );
}

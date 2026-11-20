"use client";

import Link from "next/link";
import { useState } from "react";
import { AccountPicker } from "@/components/AccountPicker";
import { useAuth } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { ApiError, newIdempotencyKey } from "@/lib/api";
import { depositApi, useFxRates, useTimeRates, type Row } from "@/lib/banking";
import { formatDateTime, formatYen, normalizeAmount } from "@/lib/format";

export default function DepositsPage() {
  return (
    <>
      <div className="page-head">
        <div>
          <p className="eyebrow">Deposit products</p>
          <h1>預金商品</h1>
          <p>普通・貯蓄・当座・通知・外貨の口座開設は窓口（顧客・口座開設）で。定期預金はここから作成できます。</p>
        </div>
      </div>
      <TimeDepositOpen />
      <FxRates />
      <ProductGuide />
    </>
  );
}

function TimeDepositOpen() {
  const { data: rates, error } = useTimeRates();
  const [source, setSource] = useState("");
  const [amount, setAmount] = useState("");
  const [term, setTerm] = useState("12");
  const [instruction, setInstruction] = useState("PAYOUT");
  const [key, setKey] = useState(newIdempotencyKey);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const [created, setCreated] = useState<Row | null>(null);
  const rate = rates?.find((r) => String(r.termMonths) === term)?.annualRate;
  const principal = Number(normalizeAmount(amount) || 0);
  const gross = rate ? Math.floor((principal * Number(rate) * Number(term)) / 1200) : 0;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      setCreated(await depositApi.openTime({ sourceAccountNo: source, amount: normalizeAmount(amount), termMonths: term,
        instruction, idempotencyKey: key }));
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
      <h2>定期預金の作成</h2>
      <ErrorBox error={error ?? err} />
      {created && (
        <SuccessBox>
          定期預金 <Link href={`/accounts/${created.accountNo}`}>{created.accountNo}</Link> を作成しました（{formatYen(created.principal)}・
          年 {Number(created.annualRate).toFixed(3)}%・満期 {created.maturityDate}）。
        </SuccessBox>
      )}
      {!rates && !error && <Loading />}
      {rates && (
        <dl className="stats">
          {rates.map((r) => (
            <div className="stat" key={r.termMonths}>
              <dt>{r.termMonths >= 12 ? `${r.termMonths / 12}年` : `${r.termMonths}か月`}</dt>
              <dd>{Number(r.annualRate).toFixed(3)}%</dd>
            </div>
          ))}
        </dl>
      )}
      <form className="wide" onSubmit={submit}>
        <div className="form-grid">
          <AccountPicker label="資金口座（普通・貯蓄）" value={source} onChange={setSource}
            filter={(a) => a.status === "ACTIVE" && (a.accountType === "ORDINARY" || a.accountType === "SAVINGS")} />
          <label>
            預入額（円）
            <input inputMode="numeric" required maxLength={12} value={amount} onChange={(e) => setAmount(e.target.value)}
              placeholder="1000000" />
            <span className="hint">10,000円以上</span>
          </label>
          <label>
            期間
            <select value={term} onChange={(e) => setTerm(e.target.value)}>
              {(rates ?? []).map((r) => (
                <option key={r.termMonths} value={r.termMonths}>
                  {r.termMonths >= 12 ? `${r.termMonths / 12}年` : `${r.termMonths}か月`}（{Number(r.annualRate).toFixed(3)}%）
                </option>
              ))}
            </select>
          </label>
          <label>
            満期時の取扱い
            <select value={instruction} onChange={(e) => setInstruction(e.target.value)}>
              <option value="PAYOUT">元利金を資金口座へ入金</option>
              <option value="ROLLOVER">元利継続（自動継続）</option>
            </select>
          </label>
        </div>
        {principal > 0 && rate && (
          <p className="hint" style={{ margin: 0 }}>
            満期時の利息（税引前・概算）{formatYen(gross)}。源泉分離課税 20.315% が差し引かれます。中途解約時は普通預金金利で計算します。
          </p>
        )}
        <div className="actions">
          <button className="btn" type="submit" disabled={busy}>{busy ? "処理中…" : "定期預金を作成する"}</button>
        </div>
      </form>
    </section>
  );
}

function FxRates() {
  const { user } = useAuth();
  const { data: rates, error } = useFxRates();
  const [currency, setCurrency] = useState("USD");
  const [ttm, setTtm] = useState("");
  const [spread, setSpread] = useState("");
  const [err, setErr] = useState<unknown>(null);
  const [done, setDone] = useState(false);

  const update = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr(null);
    setDone(false);
    try {
      await depositApi.updateRate(currency, ttm, spread);
      setDone(true);
    } catch (e2) {
      setErr(e2);
    }
  };

  return (
    <section className="card">
      <h2>外国為替相場（外貨預金）</h2>
      <p className="section-note">外貨預金への入金は TTS（円→外貨）、払出しは TTB（外貨→円）で両替します。両替は外貨預金口座の画面から行えます。</p>
      <ErrorBox error={error ?? err} />
      {done && <SuccessBox>相場を更新しました。</SuccessBox>}
      {rates && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>通貨</th><th className="num">TTS</th><th className="num">TTM（仲値）</th><th className="num">TTB</th><th>更新</th></tr></thead>
            <tbody>
              {rates.map((r) => (
                <tr key={r.currency}>
                  <td><strong>{r.currency}</strong></td>
                  <td className="num">{Number(r.tts).toFixed(2)}</td>
                  <td className="num">{Number(r.ttm).toFixed(2)}</td>
                  <td className="num">{Number(r.ttb).toFixed(2)}</td>
                  <td className="muted">{formatDateTime(r.updatedAt)}（{r.updatedBy}）</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {user?.role === "SUPERVISOR" && (
        <form className="filters" onSubmit={update} style={{ marginTop: 16 }}>
          <label>
            通貨
            <select value={currency} onChange={(e) => setCurrency(e.target.value)}>
              <option>USD</option>
              <option>EUR</option>
            </select>
          </label>
          <label>
            仲値（TTM）
            <input inputMode="decimal" required value={ttm} onChange={(e) => setTtm(e.target.value)} placeholder="152.30" />
          </label>
          <label>
            スプレッド（片側）
            <input inputMode="decimal" required value={spread} onChange={(e) => setSpread(e.target.value)} placeholder="1.00" />
          </label>
          <div className="actions"><button className="btn btn-small" type="submit">公示相場を更新</button></div>
        </form>
      )}
    </section>
  );
}

function ProductGuide() {
  const rows = [
    ["普通預金", "入出金が自由。利息は年2回（2月・8月末までを3月・9月に決算）。"],
    ["当座預金", "法人の決済用。無利息。当座貸越契約で極度額までマイナス残高（借入）にできます。"],
    ["定期預金", "3か月〜5年。作成時の金利で固定。満期時に元利金の受取りか自動継続。"],
    ["外貨預金", "米ドル・ユーロ。円口座との両替で入出金。為替相場により円換算額が変動します。"],
    ["通知預金", "50,000円以上・据置7日。払出しは2日前までに通知が必要。"],
  ];
  return (
    <section className="card">
      <h2>預金の種類</h2>
      <div className="table-wrap">
        <table>
          <tbody>
            {rows.map(([name, desc]) => (
              <tr key={name}><th style={{ width: 120 }}>{name}</th><td style={{ whiteSpace: "normal" }}>{desc}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

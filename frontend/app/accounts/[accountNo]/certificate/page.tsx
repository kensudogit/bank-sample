"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ErrorBox, Loading } from "@/components/Feedback";
import { IconArrowLeft } from "@/components/Icons";
import { batchApi, type Row } from "@/lib/banking";
import { accountTypeLabel, formatMoney, todayIso } from "@/lib/format";

/** 残高証明書：指定日の終わりの残高（取引明細の取引後残高から求める）。印刷して使う。 */
export default function CertificatePage() {
  const { accountNo } = useParams<{ accountNo: string }>();
  const [date, setDate] = useState(todayIso());
  const [data, setData] = useState<Row | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let alive = true;
    setError(null);
    batchApi.certificate(accountNo, date).then((d) => alive && setData(d)).catch((e) => alive && setError(e));
    return () => { alive = false; };
  }, [accountNo, date]);

  return (
    <>
      <div className="no-print">
        <Link href={`/accounts/${accountNo}`} className="back-link"><IconArrowLeft size={16} /> 口座 {accountNo}</Link>
        <div className="page-head">
          <div>
            <p className="eyebrow">Balance certificate</p>
            <h1>残高証明書</h1>
            <p>証明する日の終わりの残高を、取引明細から求めて表示します。発行は監査ログに記録されます。</p>
          </div>
          <div className="actions">
            <label>証明する日<input type="date" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} /></label>
            <button type="button" className="btn" onClick={() => window.print()} disabled={!data}>印刷</button>
          </div>
        </div>
        <ErrorBox error={error} />
      </div>
      {!data ? (!error && <Loading />) : (
        <section className="card certificate">
          <p className="cert-meta">発行日 {String(data.issuedAt).slice(0, 10)}</p>
          <h2 className="cert-title">残 高 証 明 書</h2>
          <p className="cert-to"><strong>{data.name}</strong> 様</p>
          <p>{data.address ?? ""}</p>
          <p className="cert-lead">下記のとおり、{formatDate(data.asOf)} 現在の預金残高を証明いたします。</p>
          <table className="cert-table">
            <tbody>
              <tr><th>口座番号</th><td>{data.accountNo}</td></tr>
              <tr><th>預金種類</th><td>{accountTypeLabel[data.accountType as keyof typeof accountTypeLabel] ?? data.accountType}（{data.currency}）</td></tr>
              <tr><th>お客さま番号</th><td>{data.customerId}</td></tr>
              <tr><th>残高</th><td className="cert-amount">{formatMoney(Number(data.balance), data.currency)}</td></tr>
            </tbody>
          </table>
          <p className="cert-note">※ 証明日の終わり（23:59:59）時点の残高です。定期預金の未払利息・当座貸越の利息は含みません。</p>
          <p className="cert-bank">永劫銀行（デモ用サンプル・架空）</p>
        </section>
      )}
    </>
  );
}

function formatDate(iso: string): string {
  const [y, m, d] = iso.split("-");
  return `${y}年${Number(m)}月${Number(d)}日`;
}

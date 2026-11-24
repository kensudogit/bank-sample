"use client";

// 口座の利息・源泉税の見込みを表示する部品。基準日を変えると再計算した結果を表示する。

import { useState } from "react";
import { ErrorBox, Loading } from "@/components/Feedback";
import { useInterestPreview } from "@/lib/api";
import { formatMoney, formatNumber, todayIso } from "@/lib/format";
import type { InterestResult } from "@/lib/types";

/** 利息・税の見込み（前回決算の翌日〜基準日の前日）。 */
export function InterestPreview({ accountNo, currency = "JPY" }: { accountNo: string; currency?: string }) {
  const [asOf, setAsOf] = useState(todayIso);
  const { data, error } = useInterestPreview(accountNo, asOf);

  return (
    <section className="card">
      <h2>利息・税金（見込み）</h2>
      <p className="section-note">
        日々の最終残高 × 年利 ÷ 365（1円未満切捨て）。税は源泉分離課税：国税15.315%・地方税5%（各1円未満切捨て）。
        基準日の前日までを計算します。未来日を指定すると、現在の残高が続いた場合の見込みです。
      </p>
      <form className="filters" onSubmit={(e) => e.preventDefault()}>
        <label>
          基準日
          <input type="date" value={asOf} required onChange={(e) => e.target.value && setAsOf(e.target.value)} />
        </label>
      </form>
      <ErrorBox error={error} />
      {!data && !error && <Loading />}
      {data && <InterestTable result={data} currency={currency} />}
    </section>
  );
}

/** 利息計算結果（計算期間・平均残高・税引前利息・国税・地方税・税引後利息）を表示する。口座解約時の結果表示にも使う。 */
export function InterestTable({ result, currency = "JPY" }: { result: InterestResult; currency?: string }) {
  // 前回の決算直後などで計算対象日が 0 日の場合は、表の代わりに説明文を出す
  if (result.days === 0) {
    return <p className="empty">前回の利息決算以降、基準日の前日までに計算対象の日がありません。</p>;
  }
  return (
    <dl className="stats">
      <div className="stat">
        <dt>計算期間</dt>
        <dd>
          {result.periodFrom}〜{result.periodTo}（{formatNumber(result.days)}日）
        </dd>
      </div>
      <div className="stat">
        <dt>平均残高</dt>
        <dd>{formatMoney(result.averageBalance, currency)}</dd>
      </div>
      <div className="stat">
        <dt>利息（税引前）</dt>
        <dd>{formatMoney(result.grossInterest, currency)}</dd>
      </div>
      <div className="stat">
        <dt>国税 15.315%</dt>
        <dd className="minus">{formatMoney(result.nationalTax, currency)}</dd>
      </div>
      <div className="stat">
        <dt>地方税 5%</dt>
        <dd className="minus">{formatMoney(result.localTax, currency)}</dd>
      </div>
      <div className="stat">
        <dt>受取利息（税引後）</dt>
        <dd className="plus">{formatMoney(result.netInterest, currency)}</dd>
      </div>
    </dl>
  );
}

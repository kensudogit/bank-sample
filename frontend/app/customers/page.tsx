"use client";

// 顧客一覧の画面（/customers、行員専用）。本人確認の状況を確認し、口座開設の申込へ進む入口。

import Link from "next/link";
import { RequireRole } from "@/components/AuthProvider";
import { ErrorBox, Loading } from "@/components/Feedback";
import { useCustomers } from "@/lib/api";
import { formatDate, idDocLabel, kycLabel } from "@/lib/format";

/** 顧客一覧ページ。窓口担当・管理者のみ表示できる（RequireRole で権限チェック）。 */
export default function CustomersPage() {
  return (
    <RequireRole roles={["TELLER", "SUPERVISOR"]}>
      <CustomerList />
    </RequireRole>
  );
}

/** 顧客の一覧表と、本人確認待ちの件数を表示する。 */
function CustomerList() {
  const { data: customers, error } = useCustomers();
  const pending = customers?.filter((c) => c.kycStatus === "PENDING").length ?? 0;

  return (
    <>
      <div className="page-head">
        <h1>顧客・口座開設</h1>
        <Link className="btn" href="/customers/new">
          口座開設の申込
        </Link>
      </div>
      <p className="section-note">
        口座開設は「申込（本人確認書類の受付）→ 本人確認（確認済／謝絶）→ 口座開設」の順に行います。
        {pending > 0 && <strong>本人確認待ち {pending} 件</strong>}
      </p>
      <ErrorBox error={error} />
      {!customers && !error && <Loading />}
      {customers && (
        <div className="card table-wrap">
          <table>
            <thead>
              <tr>
                <th>顧客番号</th>
                <th>氏名</th>
                <th>フリガナ</th>
                <th>生年月日</th>
                <th>本人確認書類</th>
                <th>本人確認</th>
                <th>登録日</th>
              </tr>
            </thead>
            <tbody>
              {customers.map((c) => (
                <tr key={c.customerId}>
                  <td>
                    <Link href={`/customers/${c.customerId}`}>{c.customerId}</Link>
                  </td>
                  <td>{c.name}</td>
                  <td>{c.nameKana ?? ""}</td>
                  <td>{formatDate(c.birthDate)}</td>
                  <td>{c.idDocType ? `${idDocLabel[c.idDocType]}（下4桁 ${c.idDocLast4}）` : "—"}</td>
                  <td>
                    <span className={`badge badge-${c.kycStatus}`}>{kycLabel[c.kycStatus]}</span>
                  </td>
                  <td className="muted">{formatDate(c.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

"use client";

import Link from "next/link";
import { ErrorBox, Loading } from "@/components/Feedback";
import { prefetchAccountDetail, useAccounts } from "@/lib/api";
import { accountTypeLabel, formatDateTime, formatYen, statusLabel } from "@/lib/format";

export default function AccountsPage() {
  const { data: accounts, error } = useAccounts();

  const total = accounts?.reduce((sum, a) => sum + a.balance, 0) ?? 0;

  return (
    <>
      <h1>口座一覧</h1>
      <ErrorBox error={error} />
      {!accounts && !error && <Loading />}
      {accounts && (
        <div className="card table-wrap">
          <table>
            <thead>
              <tr>
                <th>口座番号</th>
                <th>名義</th>
                <th>種別</th>
                <th>状態</th>
                <th className="num">残高</th>
                <th>最終更新</th>
              </tr>
            </thead>
            <tbody>
              {accounts.map((a) => {
                // Start loading the detail while the pointer / focus is on the link
                const warm = () => prefetchAccountDetail(a.accountNo);
                return (
                  <tr key={a.accountNo}>
                    <td>
                      <Link
                        href={`/accounts/${a.accountNo}`}
                        onMouseEnter={warm}
                        onFocus={warm}
                        onTouchStart={warm}
                      >
                        {a.accountNo}
                      </Link>
                    </td>
                    <td>{a.customerName}</td>
                    <td>{accountTypeLabel[a.accountType]}</td>
                    <td>
                      <span className={`badge badge-${a.status}`}>{statusLabel[a.status]}</span>
                    </td>
                    <td className="num">{formatYen(a.balance)}</td>
                    <td className="muted">{formatDateTime(a.updatedAt)}</td>
                  </tr>
                );
              })}
            </tbody>
            <tfoot>
              <tr>
                <th colSpan={4}>合計（{accounts.length} 口座）</th>
                <td className="num">
                  <strong>{formatYen(total)}</strong>
                </td>
                <td />
              </tr>
            </tfoot>
          </table>
        </div>
      )}
    </>
  );
}

"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useAuth } from "@/components/AuthProvider";
import { ErrorBox, Loading } from "@/components/Feedback";
import { IconPlus, IconSearch, IconTransfer } from "@/components/Icons";
import { prefetchAccountDetail, prefetchAccounts, useAccounts } from "@/lib/api";
import { accountTypeLabel, formatDate, formatDateTime, formatMoney, formatNumber, formatYen, statusLabel } from "@/lib/format";
import type { Account } from "@/lib/types";

const PAGE_SIZE = 50;

function greeting(): string {
  const h = new Date().getHours();
  return h < 11 ? "おはようございます" : h < 18 ? "こんにちは" : "こんばんは";
}

export default function DashboardPage() {
  const { user } = useAuth();
  const [draft, setDraft] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(1);
  const { data, error, loading, placeholder } = useAccounts({ q, page, size: PAGE_SIZE });
  // Fetch the next page in the background so "次へ" shows it immediately
  const hasNext = !placeholder && !!data?.hasNext;
  useEffect(() => {
    if (hasNext) prefetchAccounts({ q, page: page + 1, size: PAGE_SIZE });
  }, [hasNext, q, page]);
  const isCustomer = user?.customer ?? false;

  const activeCount = data?.items.filter((a) => a.status === "ACTIVE").length ?? 0;

  return (
    <>
      <div className="page-head">
        <div>
          <p className="eyebrow">{isCustomer ? "My accounts" : "Accounts"}</p>
          <h1>{isCustomer ? `${greeting()}、${user?.displayName} さま` : "口座一覧"}</h1>
        </div>
        <div className="actions">
          <Link className="btn btn-secondary" href="/transfer">
            <IconTransfer size={18} /> 振込・振替
          </Link>
          {user?.staff && (
            <Link className="btn" href="/customers/new">
              <IconPlus size={18} /> 口座開設の申込
            </Link>
          )}
        </div>
      </div>

      <ErrorBox error={error} />
      {!data && !error && <Loading />}
      {data && (
        <>
          <section className="hero" aria-label="残高の合計">
            <div className="hero-label">{isCustomer ? "総残高" : q ? `「${q}」に該当する口座の残高合計` : "全口座の残高合計"}</div>
            <div className="hero-amount">{formatYen(data.totalBalance)}</div>
            <div className="hero-meta">
              <span>
                口座数 <strong>{formatNumber(data.total)}</strong>
              </span>
              {isCustomer && (
                <span>
                  利用可能 <strong>{activeCount}</strong>
                </span>
              )}
              <span>
                更新 <strong>{new Date().toLocaleTimeString("ja-JP", { hour: "2-digit", minute: "2-digit" })}</strong>
                {loading ? "（更新中…）" : ""}
              </span>
            </div>
          </section>

          {isCustomer ? (
            <AccountTiles accounts={data.items} />
          ) : (
            <section className="card">
              <form className="filters" onSubmit={(e) => { e.preventDefault(); setQ(draft.trim()); setPage(1); }}>
                <label style={{ flex: "1 1 320px" }}>
                  口座を検索
                  <input value={draft} maxLength={50} onChange={(e) => setDraft(e.target.value)}
                    placeholder="口座番号・顧客番号・氏名・カナ" />
                </label>
                <div className="actions">
                  <button className="btn" type="submit">
                    <IconSearch size={16} /> 検索
                  </button>
                  {q && (
                    <button className="btn btn-secondary" type="button"
                      onClick={() => { setDraft(""); setQ(""); setPage(1); }}>クリア</button>
                  )}
                </div>
              </form>
              <div className={placeholder ? "is-stale" : undefined} aria-busy={placeholder}>
                <AccountTable accounts={data.items} />
              </div>
              {(data.page > 1 || data.hasNext) && (
                <div className="pager">
                  <span className="muted">
                    {page} / {Math.max(1, Math.ceil(data.total / PAGE_SIZE))} ページ
                  </span>
                  <div className="actions">
                    <button className="btn btn-secondary btn-small" type="button" disabled={page <= 1}
                      onClick={() => setPage(page - 1)}>前へ</button>
                    <button className="btn btn-secondary btn-small" type="button" disabled={!data.hasNext}
                      onClick={() => setPage(page + 1)}>次へ</button>
                  </div>
                </div>
              )}
            </section>
          )}
        </>
      )}
    </>
  );
}

function AccountTiles({ accounts }: { accounts: Account[] }) {
  if (accounts.length === 0) return <p className="empty card">ご利用の口座はありません。</p>;
  return (
    <div className="tiles">
      {accounts.map((a) => {
        const warm = () => prefetchAccountDetail(a.accountNo);
        return (
          <Link key={a.accountNo} href={`/accounts/${a.accountNo}`} className="tile" onMouseEnter={warm} onFocus={warm}
            onTouchStart={warm}>
            <div className="tile-top">
              <span className="chip">
                {accountTypeLabel[a.accountType]}預金{a.currency !== "JPY" ? ` ${a.currency}` : ""}
              </span>
              <span className={`badge badge-${a.status}`}>{statusLabel[a.status]}</span>
            </div>
            <span className="tile-no">No. {a.accountNo}</span>
            <span className="tile-balance">{formatMoney(a.balance, a.currency)}</span>
            <span className="tile-foot">最終取引 {formatDate(a.lastActivityAt)}</span>
          </Link>
        );
      })}
    </div>
  );
}

function AccountTable({ accounts }: { accounts: Account[] }) {
  if (accounts.length === 0) return <p className="empty">該当する口座はありません。</p>;
  return (
    <div className="table-wrap">
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
            const warm = () => prefetchAccountDetail(a.accountNo);
            return (
              <tr key={a.accountNo}>
                <td>
                  <Link href={`/accounts/${a.accountNo}`} onMouseEnter={warm} onFocus={warm} onTouchStart={warm}
                    className="mono" style={{ fontSize: "0.9rem", fontWeight: 700 }}>
                    {a.accountNo}
                  </Link>
                </td>
                <td>
                  {a.customerName}
                  <span className="muted"> {a.customerId}</span>
                </td>
                <td>{accountTypeLabel[a.accountType]}</td>
                <td>
                  <span className={`badge badge-${a.status}`}>{statusLabel[a.status]}</span>
                </td>
                <td className="num" style={{ fontWeight: 700 }}>{formatMoney(a.balance, a.currency)}</td>
                <td className="muted">{formatDateTime(a.updatedAt)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

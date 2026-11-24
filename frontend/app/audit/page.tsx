"use client";

// 監査ログの画面（/audit、管理者専用）。監査ログのハッシュチェーン検証、取引元帳の整合性検証、
// 監査ログの検索・ページ送りを行う。

import { useEffect, useState } from "react";
import { RequireRole, roleLabel } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { bankApi, prefetchAuditLog, useAuditLog, type AuditQuery } from "@/lib/api";
import { formatNumber } from "@/lib/format";
import type { AuditVerification, LedgerVerification, Role } from "@/lib/types";

/** 監査ログ一覧の 1 ページあたりの件数 */
const PAGE_SIZE = 50;

/** 監査ログページ。管理者（SUPERVISOR）のみ利用できる。 */
export default function AuditPage() {
  return (
    <RequireRole roles={["SUPERVISOR"]}>
      <AuditLog />
    </RequireRole>
  );
}

/** 改ざん検知（ハッシュチェーン検証）と、条件で絞り込める監査ログ一覧。 */
function AuditLog() {
  const [draft, setDraft] = useState<AuditQuery>({});
  const [filter, setFilter] = useState<AuditQuery>({});
  const [page, setPage] = useState(1);
  const { data, error, loading, placeholder } = useAuditLog({ ...filter, page, size: PAGE_SIZE });
  // Fetch the next page in the background so "次へ" shows it immediately
  const hasNext = !placeholder && !!data?.hasNext;
  useEffect(() => {
    if (hasNext) prefetchAuditLog({ ...filter, page: page + 1, size: PAGE_SIZE });
  }, [hasNext, filter, page]);
  const [verifying, setVerifying] = useState(false);
  const [verification, setVerification] = useState<AuditVerification | null>(null);
  const [verifyError, setVerifyError] = useState<unknown>(null);

  // 監査ログ全体のハッシュを先頭から計算し直し、途中の書き換え・削除がないかサーバー側で確認する
  const verify = async () => {
    setVerifying(true);
    setVerifyError(null);
    try {
      setVerification(await bankApi.auditVerify());
    } catch (err) {
      setVerifyError(err);
    } finally {
      setVerifying(false);
    }
  };

  // 入力欄の変更を draft に反映する（空欄は条件なしとして undefined にする）
  const set = (k: keyof AuditQuery) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setDraft({ ...draft, [k]: e.target.value || undefined });

  return (
    <>
      <h1>監査ログ</h1>
      <section className="card">
        <h2>改ざん検知</h2>
        <p className="section-note">
          各行は直前の行のハッシュを含めた SHA-256 でつながっています。途中の行の書き換え・削除・末尾の削除を検出します。
        </p>
        <ErrorBox error={verifyError} />
        {verification &&
          (verification.valid ? (
            <SuccessBox>正常です（{formatNumber(verification.checked)} 行を検証）。</SuccessBox>
          ) : (
            <div className="alert alert-error" role="alert">
              改ざんの可能性があります：監査ID {verification.brokenAt}（{verification.reason}）
            </div>
          ))}
        <div className="actions">
          <button className="btn btn-secondary btn-small" type="button" onClick={verify} disabled={verifying}>
            {verifying ? "検証中…" : "ハッシュチェーンを検証"}
          </button>
        </div>
      </section>

      <LedgerCheck />

      <section className="card">
        {/* 検索条件は「検索」ボタンで確定する（入力中は API を呼ばない） */}
        <form className="filters" onSubmit={(e) => { e.preventDefault(); setFilter(draft); setPage(1); }}>
          <label>
            開始日
            <input type="date" value={draft.from ?? ""} onChange={set("from")} />
          </label>
          <label>
            終了日
            <input type="date" value={draft.to ?? ""} onChange={set("to")} />
          </label>
          <label>
            利用者ID
            <input value={draft.userId ?? ""} maxLength={30} onChange={set("userId")} />
          </label>
          <label>
            操作
            <input value={draft.auditAction ?? ""} maxLength={40} onChange={set("auditAction")} placeholder="login, transfer…" />
          </label>
          <label>
            結果
            <select value={draft.outcome ?? ""} onChange={set("outcome")}>
              <option value="">すべて</option>
              <option value="SUCCESS">成功</option>
              <option value="FAILURE">失敗</option>
              <option value="DENIED">権限なし</option>
            </select>
          </label>
          <div className="actions">
            <button className="btn btn-small" type="submit">検索</button>
          </div>
        </form>

        <ErrorBox error={error} />
        {!data && !error && <Loading />}
        {data && (
          <div className={placeholder ? "is-stale" : undefined} aria-busy={placeholder}>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th className="num">ID</th>
                    <th>日時</th>
                    <th>利用者</th>
                    <th>操作</th>
                    <th>対象</th>
                    <th>結果</th>
                    <th>詳細</th>
                    <th>IP</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((e) => (
                    <tr key={e.auditId}>
                      <td className="num">{e.auditId}</td>
                      <td className="muted">{e.occurredAt.replace("T", " ").slice(0, 19)}</td>
                      <td>
                        {e.userId ?? "—"}
                        {e.userRole && <span className="muted">（{roleLabel[e.userRole as Role] ?? e.userRole}）</span>}
                      </td>
                      <td className="mono">{e.action}</td>
                      <td>{e.target ?? ""}</td>
                      <td><span className={`badge badge-${e.outcome}`}>{e.outcome}</span></td>
                      <td>{e.detail ?? ""}</td>
                      <td className="muted">{e.clientIp ?? ""}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="pager">
              <span className="muted">
                全 {formatNumber(data.total)} 件 ／ {page} ページ{loading ? "（更新中…）" : ""}
              </span>
              <div className="actions">
                <button className="btn btn-secondary btn-small" type="button" disabled={page <= 1}
                  onClick={() => setPage(page - 1)}>前へ</button>
                <button className="btn btn-secondary btn-small" type="button" disabled={!data.hasNext}
                  onClick={() => setPage(page + 1)}>次へ</button>
              </div>
            </div>
          </div>
        )}
      </section>
    </>
  );
}

/** 取引元帳の整合性検証（明細のハッシュチェーン・残高の連続性・口座残高との照合・振込の対応）。 */
function LedgerCheck() {
  const [accountNo, setAccountNo] = useState("");
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<LedgerVerification | null>(null);
  const [error, setError] = useState<unknown>(null);

  // 口座番号が空なら全口座を検証する（件数が多いと時間がかかる）
  const run = async () => {
    setRunning(true);
    setError(null);
    try {
      setResult(await bankApi.ledgerVerify(accountNo.trim() || undefined));
    } catch (err) {
      setError(err);
    } finally {
      setRunning(false);
    }
  };

  return (
    <section className="card">
      <h2>取引元帳の整合性検証</h2>
      <p className="section-note">
        口座ごとの取引明細も SHA-256 のハッシュチェーンでつながっています。明細の改ざん・削除、残高の連続性、
        口座残高と明細の一致、振込の出金・入金の対応を検証します（夜間バッチでも毎日実行）。
      </p>
      <ErrorBox error={error} />
      {result &&
        (result.valid ? (
          <SuccessBox>
            正常です（{formatNumber(result.accounts)} 口座・{formatNumber(result.transactions)} 明細を検証）。
          </SuccessBox>
        ) : (
          <>
            <div className="alert alert-error" role="alert">
              不整合を {formatNumber(result.problems.length)} 件検出しました（{formatNumber(result.accounts)} 口座・
              {formatNumber(result.transactions)} 明細を検証）。
            </div>
            <div className="table-wrap">
              <table>
                <thead><tr><th>口座</th><th className="num">取引ID</th><th>検証項目</th><th>内容</th></tr></thead>
                <tbody>
                  {result.problems.map((p, i) => (
                    <tr key={i}>
                      <td>{p.accountNo}</td>
                      <td className="num">{p.txId ?? "—"}</td>
                      <td>{p.check}</td>
                      <td>{p.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        ))}
      <form className="filters" onSubmit={(e) => { e.preventDefault(); void run(); }}>
        <label>
          口座番号（空欄で全口座）
          <input value={accountNo} maxLength={7} inputMode="numeric" onChange={(e) => setAccountNo(e.target.value)} />
        </label>
        <div className="actions">
          <button className="btn btn-secondary btn-small" type="submit" disabled={running}>
            {running ? "検証中…" : "元帳を検証"}
          </button>
        </div>
      </form>
    </section>
  );
}

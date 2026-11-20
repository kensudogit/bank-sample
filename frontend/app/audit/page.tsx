"use client";

import { useState } from "react";
import { RequireRole, roleLabel } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { bankApi, useAuditLog, type AuditQuery } from "@/lib/api";
import { formatNumber } from "@/lib/format";
import type { AuditVerification, Role } from "@/lib/types";

const PAGE_SIZE = 50;

export default function AuditPage() {
  return (
    <RequireRole roles={["SUPERVISOR"]}>
      <AuditLog />
    </RequireRole>
  );
}

function AuditLog() {
  const [draft, setDraft] = useState<AuditQuery>({});
  const [filter, setFilter] = useState<AuditQuery>({});
  const [page, setPage] = useState(1);
  const { data, error, loading } = useAuditLog({ ...filter, page, size: PAGE_SIZE });
  const [verifying, setVerifying] = useState(false);
  const [verification, setVerification] = useState<AuditVerification | null>(null);
  const [verifyError, setVerifyError] = useState<unknown>(null);

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

      <section className="card">
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
          <>
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
          </>
        )}
      </section>
    </>
  );
}

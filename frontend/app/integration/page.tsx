"use client";

// 外部連携（Kong・MuleSoft）の連携コンソール（役席のみ）。
// 稼働状況、他行の全銀データ・収納企業の請求データ・為替相場の手動送信（二者承認）、連携の実行履歴を表示する。
// 送信は「作成 → 別の役席が承認」したときだけ行う（職務分離）。承認すると、画面 → Next.js のサーバー側 API →
// Kong（bank-ops の OAuth トークン）→ MuleSoft（mTLS・署名した作成者／承認者）→ 勘定系 の順に中継される。

import { useState } from "react";
import { RequireRole, useAuth } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { formatNumber, formatYen } from "@/lib/format";
import {
  SAMPLE_BILLING, SAMPLE_FX, integrationApi, sampleZengin, useIntegrationRequests, useIntegrationRuns, useIntegrationStatus,
  type DispatchResult, type IntegrationRequest, type IntegrationRun, type SubmitKind, type ZenginResult,
} from "@/lib/integration";

export default function IntegrationPage() {
  return (
    <RequireRole roles={["SUPERVISOR"]}>
      <div className="page-head">
        <div>
          <p className="eyebrow">Integration · Kong × MuleSoft</p>
          <h1>外部連携</h1>
          <p>他行・収納企業・為替相場の提供元からのデータを、Kong（API ゲートウェイ）→ MuleSoft（変換・検証）→ 勘定系の順に取り込みます。
            連携先は OAuth 2.0 のアクセストークンで Kong の /integration/v1 を呼びます。この画面からの送信は二者承認です：
            作成した役席とは別の役席が承認したときに送信され、作成者・承認者が実行履歴に残ります。</p>
        </div>
      </div>
      <Status />
      <Submit />
      <Requests />
      <Runs />
    </RequireRole>
  );
}

/** 実行種別 → 表示名 */
const kindLabel: Record<IntegrationRun["kind"], string> = {
  ZENGIN_INCOMING: "全銀 被仕向", BILLING_IMPORT: "口座振替 請求", FX_RATES: "為替相場",
};
/** 結果 → 表示名とバッジ */
const outcomeBadge: Record<IntegrationRun["outcome"], [string, string]> = {
  SUCCESS: ["成功", "badge-SUCCESS"], PARTIAL: ["一部保留", "badge-PENDING"], FAILURE: ["失敗", "badge-FAILURE"], PROCESSING: ["処理中", "badge-PENDING"],
};
/** 依頼の状態 → 表示名とバッジ */
const requestBadge: Record<IntegrationRequest["status"], [string, string]> = {
  PENDING: ["承認待ち", "badge-PENDING"], APPROVED: ["送信中", "badge-PENDING"], SENT: ["送信済み", "badge-SUCCESS"],
  FAILED: ["送信失敗", "badge-FAILURE"], REJECTED: ["却下", "badge-FAILURE"],
};
const kindOf: Record<IntegrationRequest["kind"], SubmitKind> = { ZENGIN_INCOMING: "zengin", BILLING_IMPORT: "billing", FX_RATES: "fx" };
/** 全銀の 1 件の結果 → 表示名 */
const remitLabel: Record<string, string> = { CREDITED: "入金", HELD: "保留", DUPLICATE: "重複（取込済み）", REJECTED: "対象外" };

/** 稼働状況：Kong・MuleSoft・勘定系 */
function Status() {
  const { data, error, loading } = useIntegrationStatus();
  const item = (label: string, ok: boolean | null, sub: string) => (
    <div className="stat">
      <dt>{label}</dt>
      <dd className={ok === null ? "muted" : ok ? "plus" : "minus"}>{ok === null ? "確認中…" : ok ? "稼働中" : "停止"}</dd>
      <dd className="muted" style={{ fontWeight: 500, fontSize: "0.78rem" }}>{sub}</dd>
    </div>
  );
  const pending = !data && !error;
  return (
    <section className="card">
      <h2>稼働状況{loading && data ? <span className="muted">（更新中…）</span> : null}</h2>
      <ErrorBox error={error} />
      <dl className="stats">
        {item("API ゲートウェイ（Kong）", pending ? null : !!data, "画面の API と外部連携の入口")}
        {item("連携基盤（MuleSoft）", pending ? null : !!data, data ? `ランタイム: ${data.runtime}` : "—")}
        {item("勘定系（Liberty）", pending ? null : data ? data.core === "UP" : false, "MuleSoft から見た接続状態")}
      </dl>
    </section>
  );
}

/** 送信フォームの種類ごとの設定 */
const FORMS: Record<SubmitKind, { label: string; note: string; accept: string; sample: () => string }> = {
  zengin: {
    label: "全銀 被仕向（固定長）",
    note: "全銀フォーマット（総合振込・120 桁固定長）。件数と合計金額を検証し、口座番号と受取人名が一致すれば入金、不一致は保留します。同じデータを再送しても二重に入金されません。",
    accept: ".txt,.dat,text/plain",
    sample: () => sampleZengin(),
  },
  billing: {
    label: "口座振替 請求（CSV）",
    note: "1 行目は見出し（収納企業コード,お客さま番号,金額,引落日）。金額のカンマ、引落日の yyyyMMdd も受け付けます。",
    accept: ".csv,text/csv",
    sample: () => SAMPLE_BILLING,
  },
  fx: {
    label: "為替相場（JSON）",
    note: "相場の提供元の形式（provider・rates[currency, mid, spread]）。当行が扱う USD・EUR だけを更新し、それ以外は対象外にします。",
    accept: ".json,application/json",
    sample: () => SAMPLE_FX,
  },
};

const contentTypes: Record<SubmitKind, string> = { zengin: "text/plain", billing: "text/csv", fx: "application/json" };

/** データの送信（ファイルを選ぶか、テキストを貼り付ける） */
function Submit() {
  const [kind, setKind] = useState<SubmitKind>("zengin");
  const [text, setText] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [charset, setCharset] = useState("utf-8");
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [created, setCreated] = useState<IntegrationRequest | null>(null);
  const form = FORMS[kind];

  const switchKind = (k: SubmitKind) => {
    setKind(k);
    setText("");
    setFile(null);
    setError(null);
    setCreated(null);
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    setCreated(null);
    try {
      const body: BodyInit = file ?? text;
      // 全銀データは文字コードを選べる（Shift_JIS のファイルはそのままのバイト列で送る）
      const ct = kind === "fx" ? contentTypes.fx : `${contentTypes[kind]}; charset=${file && kind === "zengin" ? charset : "utf-8"}`;
      setCreated(await integrationApi.createRequest(kind, body, ct, file?.name ?? null, comment.trim() || null));
      setText("");
      setFile(null);
      setComment("");
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card">
      <h2>データの送信（承認依頼の作成）</h2>
      <div className="tabs" role="tablist" aria-label="データの種類">
        {(Object.keys(FORMS) as SubmitKind[]).map((k) => (
          <button key={k} type="button" role="tab" className="tab" aria-selected={kind === k} onClick={() => switchKind(k)}>
            {FORMS[k].label}
          </button>
        ))}
      </div>
      <p className="section-note">{form.note}</p>
      <form onSubmit={submit} style={{ maxWidth: "none" }}>
        <div className="filters" style={{ marginBottom: 8 }}>
          <label>
            ファイル
            <input type="file" accept={form.accept} onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </label>
          {kind === "zengin" && (
            <label>
              文字コード（ファイル）
              <select value={charset} onChange={(e) => setCharset(e.target.value)}>
                <option value="utf-8">UTF-8</option>
                <option value="shift_jis">Shift_JIS</option>
              </select>
            </label>
          )}
          <button type="button" className="btn btn-secondary btn-small" onClick={() => { setFile(null); setText(form.sample()); }}>
            サンプルを入れる
          </button>
        </div>
        {!file && (
          <label>
            データ（貼り付け）
            <textarea className="mono" rows={kind === "zengin" ? 6 : 5} value={text} onChange={(e) => setText(e.target.value)}
              style={{ fontSize: "0.75rem", whiteSpace: "pre", overflowX: "auto" }} spellCheck={false} />
          </label>
        )}
        {file && <p className="hint">選択中のファイル：{file.name}（{formatNumber(file.size)} バイト）</p>}
        <label>
          依頼のコメント（任意）
          <input value={comment} maxLength={200} onChange={(e) => setComment(e.target.value)} />
        </label>
        <div className="actions">
          <button className="btn" type="submit" disabled={busy || (!file && text.trim() === "")}>
            {busy ? "作成中…" : "承認依頼を作成"}
          </button>
        </div>
      </form>
      <ErrorBox error={error} />
      {created && (
        <SuccessBox>
          承認依頼を作成しました（{formatNumber(created.payloadBytes)} バイト・SHA-256 {created.payloadSha256.slice(0, 12)}…）。
          別の役席が「承認依頼」から承認すると、Kong 経由で送信されます。
        </SuccessBox>
      )}
    </section>
  );
}

/** 送信結果の表示 */
function Result({ kind, data }: { kind: SubmitKind; data: any }) {
  if (kind === "zengin") {
    const z = data as ZenginResult;
    return (
      <>
        <SuccessBox>
          仕向銀行 {z.senderBankCode}・{z.remitterNameKana}（取組日 {z.tradeDate}）の {z.records} 件・{formatYen(z.totalAmount)} を処理しました：
          入金 {z.summary.credited}・保留 {z.summary.held}・重複 {z.summary.duplicate}・対象外 {z.summary.rejected}
        </SuccessBox>
        <div className="table-wrap">
          <table>
            <thead><tr><th>行</th><th>口座</th><th className="num">金額</th><th>照会番号</th><th>結果</th><th>理由</th></tr></thead>
            <tbody>
              {z.results.map((r) => (
                <tr key={r.line}>
                  <td>{r.line}</td><td>{r.accountNo}</td><td className="num">{formatYen(r.amount)}</td><td className="mono">{r.referenceNo}</td>
                  <td><span className={`badge ${r.status === "CREDITED" ? "badge-SUCCESS" : r.status === "HELD" ? "badge-PENDING" : "badge-FAILURE"}`}>
                    {remitLabel[r.status] ?? r.status}</span></td>
                  <td className="muted">{r.note ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </>
    );
  }
  if (kind === "billing") {
    const results = (data.results ?? []) as { billerCode: string; payerNumber: string; result: string }[];
    return (
      <>
        <SuccessBox>{data.rows} 行を処理しました（取込 {results.filter((r) => r.result === "取込").length} 件）。</SuccessBox>
        <div className="table-wrap">
          <table>
            <thead><tr><th>収納企業</th><th>お客さま番号</th><th>結果</th></tr></thead>
            <tbody>
              {results.map((r, i) => (
                <tr key={i}><td>{r.billerCode}</td><td className="mono">{r.payerNumber}</td><td>{r.result}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </>
    );
  }
  return (
    <SuccessBox>
      {data.provider} の相場で {(data.updated as string[]).join("・") || "なし"} を更新しました
      {(data.skipped as string[]).length > 0 ? `（対象外：${(data.skipped as string[]).join("・")}）` : ""}。
    </SuccessBox>
  );
}

/** 承認依頼の一覧：承認して送信・却下・失敗した送信の再送 */
function Requests() {
  const { user } = useAuth();
  const { data, error, loading, reload } = useIntegrationRequests();
  const [busyId, setBusyId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [outcome, setOutcome] = useState<DispatchResult | null>(null);
  const [rejected, setRejected] = useState<string | null>(null);

  const run = async (id: string, fn: () => Promise<unknown>) => {
    setBusyId(id);
    setActionError(null);
    setOutcome(null);
    setRejected(null);
    try {
      const r = await fn();
      if (r && typeof r === "object" && "httpStatus" in r) setOutcome(r as DispatchResult);
      else setRejected(id);
    } catch (e) {
      setActionError(e);
    } finally {
      setBusyId(null);
      reload();
    }
  };
  const reject = (id: string) => {
    const reason = window.prompt("却下の理由を入力してください");
    if (reason && reason.trim()) run(id, () => integrationApi.reject(id, reason.trim()));
  };

  return (
    <section className="card table-wrap">
      <div className="chart-head">
        <h2 style={{ margin: 0 }}>承認依頼</h2>
        <button type="button" className="btn btn-secondary btn-small" onClick={reload} disabled={loading}>
          {loading ? "更新中…" : "再読み込み"}
        </button>
      </div>
      <p className="section-note">作成した役席は自分の依頼を承認・却下できません。承認すると、承認された内容（SHA-256 が一致するもの）だけが送信されます。
        送信に失敗した依頼は、同じ依頼として再送できます（二重には取り込まれません）。</p>
      <ErrorBox error={error} />
      <ErrorBox error={actionError} />
      {rejected && <SuccessBox>依頼を却下しました。</SuccessBox>}
      {outcome && <DispatchOutcome outcome={outcome} />}
      {!data && !error && <Loading />}
      {data && data.length === 0 && <p className="muted">承認依頼はありません。</p>}
      {data && data.length > 0 && (
        <table>
          <thead><tr><th>作成</th><th>種類</th><th>データ</th><th>作成者</th><th>状態</th><th>承認者</th><th>操作</th></tr></thead>
          <tbody>
            {data.map((r) => {
              const mine = r.maker === user?.userId;
              const busy = busyId === r.requestId;
              return (
                <tr key={r.requestId}>
                  <td className="muted">{localTime(r.createdAt)}</td>
                  <td>{kindLabel[r.kind] ?? r.kind}</td>
                  <td style={{ whiteSpace: "normal", minWidth: 200 }}>
                    {r.fileName ?? "（貼り付け）"}・{formatNumber(r.payloadBytes)} バイト
                    <div className="mono muted" title={r.payloadSha256}>SHA-256 {r.payloadSha256.slice(0, 12)}…</div>
                    {r.commentText && <div className="muted">{r.commentText}</div>}
                  </td>
                  <td>{r.maker}</td>
                  <td>
                    <span className={`badge ${requestBadge[r.status]?.[1] ?? ""}`}>{requestBadge[r.status]?.[0] ?? r.status}</span>
                    {r.attempts > 1 && <span className="muted">（{r.attempts} 回目）</span>}
                    {r.rejectReason && <div className="muted">{r.rejectReason}</div>}
                  </td>
                  <td>{r.approver ?? "—"}</td>
                  <td>
                    {r.status === "PENDING" && mine && <span className="muted">別の役席の承認待ち</span>}
                    {r.status === "PENDING" && !mine && (
                      <div className="actions" style={{ margin: 0 }}>
                        <button type="button" className="btn btn-small" disabled={busy} onClick={() => run(r.requestId, () => integrationApi.approve(r.requestId))}>
                          {busy ? "送信中…" : "承認して送信"}
                        </button>
                        <button type="button" className="btn btn-secondary btn-small" disabled={busy} onClick={() => reject(r.requestId)}>却下</button>
                      </div>
                    )}
                    {(r.status === "FAILED" || r.status === "APPROVED") && (
                      <button type="button" className="btn btn-secondary btn-small" disabled={busy} onClick={() => run(r.requestId, () => integrationApi.retry(r.requestId))}>
                        {busy ? "送信中…" : "再送"}
                      </button>
                    )}
                    {r.status === "SENT" && <span className="muted">HTTP {r.resultStatus}</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </section>
  );
}

/** 承認・再送の結果：成功なら取込結果、失敗なら連携のエラー */
function DispatchOutcome({ outcome }: { outcome: DispatchResult }) {
  if (outcome.httpStatus >= 200 && outcome.httpStatus < 300) return <Result kind={outcome.kind} data={outcome.result} />;
  const p = outcome.result as { title?: string; detail?: string; errors?: { line?: number; message: string }[] } | null;
  return (
    <div className="error-box" role="alert">
      送信しましたが、連携が受け付けませんでした（HTTP {outcome.httpStatus}）：{p?.title ?? "エラー"}{p?.detail ? `：${p.detail}` : ""}
      {outcome.httpStatus >= 500 || outcome.httpStatus === 0 ? "。時間をおいて「再送」してください。" : ""}
      {p?.errors && p.errors.length > 0 && (
        <ul>{p.errors.map((e, i) => <li key={i}>{e.line ? `${e.line} 行目：` : ""}{e.message}</li>)}</ul>
      )}
    </div>
  );
}

/** ISO 日時（UTC）をブラウザの時刻で「M/d HH:mm:ss」にする */
function localTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("ja-JP", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

/** 連携の実行履歴（MuleSoft が勘定系の Oracle に記録。新しい順・最大 50 件） */
function Runs() {
  const { data, error, loading, reload } = useIntegrationRuns();
  return (
    <section className="card table-wrap">
      <div className="chart-head">
        <h2 style={{ margin: 0 }}>連携の実行履歴</h2>
        <button type="button" className="btn btn-secondary btn-small" onClick={reload} disabled={loading}>
          {loading ? "更新中…" : "再読み込み"}
        </button>
      </div>
      <ErrorBox error={error} />
      {!data && !error && <Loading />}
      {data && data.length === 0 && <p className="muted">まだ実行履歴はありません。</p>}
      {data && data.length > 0 && (
        <table>
          <thead><tr><th>開始</th><th>種類</th><th>結果</th><th>内容</th><th>連携先</th><th className="num">所要</th><th>要求 ID</th></tr></thead>
          <tbody>
            {data.map((r) => (
              <tr key={r.runId}>
                <td className="muted">{localTime(r.startedAt)}</td>
                <td>{kindLabel[r.kind] ?? r.kind}</td>
                <td><span className={`badge ${outcomeBadge[r.outcome]?.[1] ?? ""}`}>{outcomeBadge[r.outcome]?.[0] ?? r.outcome}</span></td>
                <td style={{ whiteSpace: "normal", minWidth: 240 }}>{r.summary}</td>
                <td>
                  {r.consumer}
                  {r.operator && <span className="muted">（{r.operator}{r.approver ? ` → 承認 ${r.approver}` : ""}）</span>}
                  {(r.attempts ?? 1) > 1 && <span className="muted">・{r.attempts} 回目</span>}
                </td>
                <td className="num">{formatNumber(r.durationMs)} ms</td>
                <td className="mono muted" title={r.requestId}>{r.requestId.slice(0, 8)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

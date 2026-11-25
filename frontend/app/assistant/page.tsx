"use client";

// AI アシスタント（Claude）：行員の質問に、勘定系の参照 API を使って答える（窓口・役席）。
// 行員の委任（On-Behalf-Of）で動き、勘定系は「行員の権限 ∩ Agent の権限」で判定する。
// 振込などお金が動く操作は実行せず、提案として表示する（行員が振込画面で確認して実行する）。
// 実行ごとに、呼んだツール・トークン数・費用・追跡用の ID を表示する（課金は「コスト・課金」画面で集計）。

import Link from "next/link";
import { useState } from "react";
import { RequireRole } from "@/components/AuthProvider";
import { ErrorBox } from "@/components/Feedback";
import { Markdown } from "@/components/Markdown";
import { agentApi, type AgentRunResult } from "@/lib/agent";
import { formatNumber, formatYen } from "@/lib/format";

const statusLabel: Record<AgentRunResult["status"], [string, string]> = {
  COMPLETED: ["完了", "badge-SUCCESS"], LIMITED: ["上限で打ち切り", "badge-PENDING"], BLOCKED: ["予算超過で停止", "badge-FAILURE"], FAILED: ["失敗", "badge-FAILURE"],
};
const toolLabel: Record<string, string> = {
  search_accounts: "口座の検索", get_account: "口座の詳細", get_transactions: "取引明細", list_customers: "顧客の一覧",
  get_customer: "顧客の詳細", get_fx_rates: "為替相場", propose_transfer: "振込の提案",
};
const callStatus: Record<string, [string, string]> = {
  OK: ["取得", "badge-SUCCESS"], DENIED: ["権限なし", "badge-FAILURE"], ERROR: ["エラー", "badge-FAILURE"], INVALID: ["引数不正", "badge-FAILURE"], PROPOSED: ["提案", "badge-PENDING"],
};

const EXAMPLES = [
  "口座 1000001 の残高と、直近の取引を教えてください",
  "米ドルとユーロの公示相場は？",
  "口座 1000001 から 1000002 へ 5,000 円の振込を準備して",
];

export default function AssistantPage() {
  const [prompt, setPrompt] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<AgentRunResult | null>(null);

  const ask = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy || !prompt.trim()) return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await agentApi.run(prompt.trim()));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <RequireRole roles={["TELLER", "SUPERVISOR"]}>
      <div className="page-head">
        <div>
          <p className="eyebrow">AI Assistant · Claude</p>
          <h1>AI アシスタント</h1>
          <p>口座・取引・顧客・為替相場について質問できます。アシスタントはあなたの権限の範囲で、参照だけを行います（Agent に許可された範囲との共通部分）。
            お金が動く操作は実行せず、提案として表示します。質問と呼び出しは監査ログと実行履歴に残ります。</p>
        </div>
      </div>

      <section className="card">
        <form onSubmit={ask} style={{ maxWidth: "none" }}>
          <label>
            質問
            <textarea rows={3} maxLength={2000} value={prompt} onChange={(e) => setPrompt(e.target.value)} placeholder="例：口座 1000001 の残高は？" />
          </label>
          <div className="filters" style={{ marginBottom: 8 }}>
            {EXAMPLES.map((ex) => (
              <button key={ex} type="button" className="btn btn-secondary btn-small" onClick={() => setPrompt(ex)}>{ex}</button>
            ))}
          </div>
          <div className="actions">
            <button className="btn" type="submit" disabled={busy || !prompt.trim()}>{busy ? "回答を作成中…" : "質問する"}</button>
          </div>
        </form>
        <ErrorBox error={error} />
      </section>

      {result && (
        <>
          <section className="card">
            <div className="chart-head">
              <h2 style={{ margin: 0 }}>回答</h2>
              <span className={`badge ${statusLabel[result.status]?.[1] ?? ""}`}>{statusLabel[result.status]?.[0] ?? result.status}</span>
            </div>
            {result.answer ? <Markdown text={result.answer} /> : <p className="muted">（回答なし）</p>}
            {result.proposals.length > 0 && (
              <div className="section-note">
                <strong>提案（未実行）</strong>
                <ul>
                  {result.proposals.map((p, i) => (
                    <li key={i}>
                      振込：{p.fromAccountNo} → {p.toAccountNo}・{formatYen(p.amount)}（{p.memo}）。
                      内容を確認して <Link href="/transfer">振込画面</Link> で実行してください。
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </section>

          <section className="card table-wrap">
            <h2>実行の記録</h2>
            <dl className="stats">
              <div className="stat"><dt>費用</dt><dd>{result.costJpy == null ? "—" : `${result.costJpy.toFixed(2)} 円`}</dd></div>
              <div className="stat"><dt>入力・出力トークン</dt><dd>{formatNumber(result.usage.inputTokens)}・{formatNumber(result.usage.outputTokens)}</dd></div>
              <div className="stat"><dt>キャッシュ読み込み</dt><dd>{formatNumber(result.usage.cacheReadTokens)}</dd></div>
              <div className="stat"><dt>モデル・回数</dt><dd className="mono" style={{ fontSize: "0.85rem" }}>{result.model}・{result.steps} 回</dd></div>
            </dl>
            {result.toolCalls.length > 0 ? (
              <table>
                <thead><tr><th>#</th><th>ツール</th><th>結果</th><th className="num">所要</th></tr></thead>
                <tbody>
                  {result.toolCalls.map((c, i) => (
                    <tr key={i}>
                      <td>{i + 1}</td>
                      <td>{toolLabel[c.name] ?? c.name}<span className="muted mono">（{c.name}）</span></td>
                      <td><span className={`badge ${callStatus[c.status]?.[1] ?? ""}`}>{callStatus[c.status]?.[0] ?? c.status}</span>
                        {c.httpStatus ? <span className="muted">（HTTP {c.httpStatus}）</span> : null}</td>
                      <td className="num">{formatNumber(c.ms)} ms</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : <p className="muted">ツールは使いませんでした。</p>}
            <p className="hint mono">実行 ID {result.runId}・トレース ID {result.traceId}</p>
          </section>
        </>
      )}
    </RequireRole>
  );
}

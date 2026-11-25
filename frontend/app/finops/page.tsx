"use client";

// コスト・課金（役席）：課金の可視化（配賦）とコスト予測。
// API 利用（Kong・MuleSoft・勘定系の単価）・LLM（AI アシスタントのトークン）・インフラ（Railway）の当月実績、
// 月末の見込み（幅と信頼度つき）、部門・配賦先ごとの予算の状況、AI アシスタントの実行の追跡、単価表を表示する。

import { useState } from "react";
import { RequireRole } from "@/components/AuthProvider";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { finopsApi, useAgentRuns, useFinOpsSummary, useRateCard, type BudgetStatus, type FinOpsSummary } from "@/lib/agent";
import { formatNumber } from "@/lib/format";

const yen2 = (n: number | null | undefined) => (n == null ? "—" : `${Number(n).toLocaleString("ja-JP", { maximumFractionDigits: 2, minimumFractionDigits: 0 })} 円`);
const budgetBadge: Record<BudgetStatus, [string, string]> = {
  OK: ["予算内", "badge-SUCCESS"], WARN: ["注意（80% 以上）", "badge-PENDING"], OVER: ["超過見込み", "badge-FAILURE"], NO_BUDGET: ["予算なし", "badge-PENDING"],
};
const subjectTypeLabel: Record<string, string> = { CONSUMER: "連携先", AGENT: "AI Agent", CHANNEL: "経路", INFRA: "インフラ" };
const sourceLabel: Record<string, string> = { GATEWAY: "API 利用（Kong・MuleSoft・勘定系）", LLM: "LLM（AI アシスタント）", INFRA: "インフラ（Railway）" };

export default function FinOpsPage() {
  const [month, setMonth] = useState<string>("");
  const { data, error, loading, reload } = useFinOpsSummary(month || undefined);
  return (
    <RequireRole roles={["SUPERVISOR"]}>
      <div className="page-head">
        <div>
          <p className="eyebrow">FinOps · Chargeback & Forecast</p>
          <h1>コスト・課金</h1>
          <p>API の利用（Kong・MuleSoft・勘定系）、AI アシスタントの LLM、インフラ（Railway）の費用を、連携先・Agent・経路ごとに部門へ配賦し、月末の見込みを予算と比べます。
            単価は仮置きです（単価表）。ベンダーの請求書と照合して更新してください。</p>
        </div>
      </div>
      <section className="card">
        <div className="filters">
          <label>
            対象月
            <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} />
          </label>
          <button type="button" className="btn btn-secondary btn-small" onClick={reload} disabled={loading}>{loading ? "更新中…" : "再読み込み"}</button>
        </div>
        <ErrorBox error={error} />
        {!data && !error && <Loading />}
        {data && <Totals data={data} />}
      </section>
      {data && <Departments data={data} />}
      {data && <Subjects data={data} />}
      {data && <Daily data={data} />}
      {data && <Apis data={data} />}
      {data && <Infra data={data} />}
      <AgentRuns />
      <RateCard />
    </RequireRole>
  );
}

function Totals({ data }: { data: FinOpsSummary }) {
  const t = data.total;
  return (
    <>
      <dl className="stats">
        <div className="stat"><dt>当月実績（{data.month}・{data.asOf} まで）</dt><dd>{yen2(t.monthToDateJpy)}</dd></div>
        <div className="stat"><dt>月末の見込み</dt><dd>{yen2(t.forecastJpy)}</dd>
          <dd className="muted" style={{ fontWeight: 500, fontSize: "0.78rem" }}>幅 {yen2(t.forecastLowJpy)} 〜 {yen2(t.forecastHighJpy)}{t.lowConfidence ? "・一部は信頼度「低」" : ""}</dd></div>
        <div className="stat"><dt>予算（合計）</dt><dd>{yen2(t.budgetJpy)}</dd>
          <dd><span className={`badge ${budgetBadge[t.budgetStatus][1]}`}>{budgetBadge[t.budgetStatus][0]}</span></dd></div>
        <div className="stat"><dt>固定費（ライセンス）</dt><dd>{yen2(t.fixedJpy)}</dd></div>
      </dl>
      <div className="table-wrap">
        <table>
          <thead><tr><th>費目</th><th className="num">要求数</th><th className="num">トークン（入力・出力・キャッシュ）</th><th className="num">当月実績</th></tr></thead>
          <tbody>
            {data.bySource.map((s) => (
              <tr key={s.source}>
                <td>{sourceLabel[s.source] ?? s.source}</td>
                <td className="num">{s.requests != null ? formatNumber(s.requests) : "—"}</td>
                <td className="num">{s.inputTokens != null ? `${formatNumber(s.inputTokens)}・${formatNumber(s.outputTokens ?? 0)}・${formatNumber(s.cacheReadTokens ?? 0)}` : "—"}</td>
                <td className="num">{yen2(s.costJpy)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint">{data.assumptions.method}。為替 1 USD = {data.assumptions.fxUsdJpy} 円。{data.assumptions.note}</p>
    </>
  );
}

function Departments({ data }: { data: FinOpsSummary }) {
  return (
    <section className="card table-wrap">
      <h2>部門別</h2>
      <table>
        <thead><tr><th>部門</th><th className="num">当月実績</th><th className="num">月末の見込み</th><th className="num">予算</th><th>状況</th></tr></thead>
        <tbody>
          {data.byDepartment.map((d) => (
            <tr key={d.department}>
              <td>{d.department}</td><td className="num">{yen2(d.monthToDateJpy)}</td><td className="num">{yen2(d.forecastJpy)}</td>
              <td className="num">{yen2(d.budgetJpy)}</td><td><span className={`badge ${budgetBadge[d.budgetStatus][1]}`}>{budgetBadge[d.budgetStatus][0]}</span></td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function Subjects({ data }: { data: FinOpsSummary }) {
  const [editing, setEditing] = useState<string | null>(null);
  const [value, setValue] = useState("");
  const [error, setError] = useState<unknown>(null);
  const save = async (type: string, id: string) => {
    setError(null);
    try {
      await finopsApi.updateBudget(type, id, Number(value));
      setEditing(null);
    } catch (e) {
      setError(e);
    }
  };
  return (
    <section className="card table-wrap">
      <h2>配賦先別（連携先・AI Agent・経路・インフラ）</h2>
      <p className="section-note">「急増」は当日の費用が直近 7 日の日平均の 3 倍を超えたもの。AI Agent は予算に達すると新しい実行を止めます（停止）。</p>
      <ErrorBox error={error} />
      <table>
        <thead><tr><th>配賦先</th><th>部門</th><th className="num">要求・トークン</th><th className="num">当月実績</th><th className="num">月末の見込み（幅）</th><th>信頼度</th><th className="num">予算</th><th>状況</th></tr></thead>
        <tbody>
          {data.bySubject.map((s) => {
            const key = `${s.subjectType}:${s.subjectId}`;
            return (
              <tr key={key}>
                <td>{s.subjectId}<div className="muted">{subjectTypeLabel[s.subjectType] ?? s.subjectType}{s.hardStop ? "・超過時は停止" : ""}</div></td>
                <td>{s.department}{s.owner && <div className="muted">{s.owner}</div>}</td>
                <td className="num">{formatNumber(s.requests)}{s.tokens > 0 && <div className="muted">{formatNumber(s.tokens)} tok</div>}</td>
                <td className="num">{yen2(s.monthToDateJpy)}</td>
                <td className="num">{yen2(s.forecastJpy)}<div className="muted">{yen2(s.forecastLowJpy)}〜{yen2(s.forecastHighJpy)}</div></td>
                <td>{s.confidence}{s.anomaly && <span className="badge badge-FAILURE" style={{ marginLeft: 4 }}>急増</span>}</td>
                <td className="num">
                  {editing === key ? (
                    <span>
                      <input style={{ width: 90 }} inputMode="numeric" value={value} onChange={(e) => setValue(e.target.value.replace(/[^0-9]/g, ""))} aria-label={`${s.subjectId} の予算`} />
                      <button type="button" className="btn btn-small" onClick={() => save(s.subjectType, s.subjectId)} disabled={!value}>保存</button>
                    </span>
                  ) : (
                    <button type="button" className="btn btn-secondary btn-small" onClick={() => { setEditing(key); setValue(String(s.budgetJpy)); }}>
                      {yen2(s.budgetJpy)}
                    </button>
                  )}
                </td>
                <td><span className={`badge ${budgetBadge[s.budgetStatus][1]}`}>{budgetBadge[s.budgetStatus][0]}</span></td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </section>
  );
}

function Daily({ data }: { data: FinOpsSummary }) {
  const max = Math.max(1, ...data.daily.map((d) => d.gatewayJpy + d.llmJpy + d.infraJpy));
  return (
    <section className="card">
      <h2>日別の費用</h2>
      <div role="img" aria-label="日別の費用（API 利用・LLM・インフラ）" style={{ display: "flex", alignItems: "flex-end", gap: 3, height: 140, borderBottom: "1px solid var(--border, #ccc)" }}>
        {data.daily.map((d) => {
          const h = (v: number) => `${(v / max) * 130}px`;
          return (
            <div key={d.date} title={`${d.date}：API ${yen2(d.gatewayJpy)}・LLM ${yen2(d.llmJpy)}・インフラ ${yen2(d.infraJpy)}`} style={{ flex: 1, display: "flex", flexDirection: "column-reverse" }}>
              <div style={{ height: h(d.gatewayJpy), background: "#3b82f6" }} />
              <div style={{ height: h(d.llmJpy), background: "#f59e0b" }} />
              <div style={{ height: h(d.infraJpy), background: "#10b981" }} />
            </div>
          );
        })}
      </div>
      <p className="hint"><span style={{ color: "#3b82f6" }}>■</span> API 利用　<span style={{ color: "#f59e0b" }}>■</span> LLM　<span style={{ color: "#10b981" }}>■</span> インフラ（{data.daily[0]?.date} 〜 {data.daily.at(-1)?.date}）</p>
    </section>
  );
}

function Apis({ data }: { data: FinOpsSummary }) {
  return (
    <section className="card table-wrap">
      <h2>API 別</h2>
      <table>
        <thead><tr><th>API（Kong のサービス）</th><th className="num">要求数</th><th className="num">平均遅延</th><th className="num">5xx</th><th className="num">当月実績</th></tr></thead>
        <tbody>
          {data.byApi.map((a) => (
            <tr key={`${a.source}:${a.apiId}`}>
              <td className="mono">{a.apiId}</td><td className="num">{formatNumber(a.requests)}</td>
              <td className="num">{a.avgLatencyMs == null ? "—" : `${formatNumber(Math.round(a.avgLatencyMs))} ms`}</td>
              <td className="num">{formatNumber(a.errors)}</td><td className="num">{yen2(a.costJpy)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {data.byApi.length === 0 && <p className="muted">まだ記録がありません。</p>}
    </section>
  );
}

function Infra({ data }: { data: FinOpsSummary }) {
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const sync = async () => {
    setBusy(true);
    setMsg(null);
    setError(null);
    try {
      const r = await finopsApi.syncInfra();
      setMsg(r.status === "SYNCED" ? `取り込みました（${r.days} 日分・${r.rows} 行）。` : r.message ?? r.status);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="card table-wrap">
      <div className="chart-head">
        <h2 style={{ margin: 0 }}>インフラ（Railway）</h2>
        <button type="button" className="btn btn-secondary btn-small" onClick={sync} disabled={busy}>{busy ? "取込中…" : "Railway の使用量を取り込む"}</button>
      </div>
      <p className="section-note">夜間バッチ（INFRA_COST_SYNC）で前日・当日分を取り込みます。Railway の API トークン（RAILWAY_API_TOKEN）が必要です。</p>
      <ErrorBox error={error} />
      {msg && <SuccessBox>{msg}</SuccessBox>}
      {data.infra.length === 0 ? <p className="muted">まだ取り込んでいません。</p> : (
        <table>
          <thead><tr><th>サービス</th><th className="num">vCPU・分</th><th className="num">メモリ GB・分</th><th className="num">外向き GB</th><th className="num">USD</th><th className="num">円</th></tr></thead>
          <tbody>
            {data.infra.map((i) => (
              <tr key={i.serviceName}>
                <td>{i.serviceName}</td><td className="num">{formatNumber(Math.round(i.cpuVcpuMin))}</td><td className="num">{formatNumber(Math.round(i.memoryGbMin))}</td>
                <td className="num">{Number(i.egressGb).toFixed(3)}</td><td className="num">{Number(i.costUsd).toFixed(2)}</td><td className="num">{yen2(i.costJpy)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function AgentRuns() {
  const { data, error } = useAgentRuns();
  return (
    <section className="card table-wrap">
      <h2>AI アシスタントの実行（追跡）</h2>
      <ErrorBox error={error} />
      {!data && !error && <Loading />}
      {data && data.length === 0 && <p className="muted">まだ実行はありません。</p>}
      {data && data.length > 0 && (
        <table>
          <thead><tr><th>開始</th><th>行員</th><th>Agent・モデル</th><th>状態</th><th>ツール</th><th className="num">トークン（入・出）</th><th className="num">費用</th><th>トレース</th></tr></thead>
          <tbody>
            {data.map((r) => (
              <tr key={r.runId}>
                <td className="muted">{r.startedAt?.replace("T", " ").slice(0, 16)}</td>
                <td>{r.principal}</td>
                <td>{r.agentId}<div className="muted mono">{r.model}</div></td>
                <td>{r.status}{r.deniedCalls > 0 && <div className="muted">拒否 {r.deniedCalls}</div>}</td>
                <td style={{ whiteSpace: "normal", minWidth: 180 }} className="mono muted">{r.toolCalls || "—"}</td>
                <td className="num">{formatNumber(r.inputTokens)}・{formatNumber(r.outputTokens)}</td>
                <td className="num">{yen2(r.costJpy)}</td>
                <td className="mono muted" title={r.traceId ?? ""}>{r.traceId?.slice(0, 8) ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function RateCard() {
  const { data, error } = useRateCard();
  return (
    <section className="card table-wrap">
      <h2>単価表（仮置き）</h2>
      <ErrorBox error={error} />
      {data && (
        <table>
          <thead><tr><th>項目</th><th>単位</th><th className="num">単価</th><th>説明</th></tr></thead>
          <tbody>
            {data.map((r) => (
              <tr key={r.rateKey}><td className="mono">{r.rateKey}</td><td>{r.unit}</td><td className="num">{r.unitPrice} {r.currency}</td><td className="muted">{r.description}</td></tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

// 監視：Prometheus 形式の指標と、OpenTelemetry のトレース（OTLP/HTTP JSON。依存パッケージなし）。
// Kong から届いた traceparent（W3C Trace Context）を引き継いで MuleSoft のスパンを作り、勘定系の呼び出しにも伝える。
// OTEL_EXPORTER_OTLP_ENDPOINT（例 http://otel-collector:4318）が未設定ならトレースは送らない。

import { randomBytes } from "node:crypto";

const hex = (n) => randomBytes(n).toString("hex");

export function createMetrics() {
  const counters = new Map();
  const sums = new Map();
  const key = (name, labels) => `${name}{${Object.entries(labels).map(([k, v]) => `${k}="${String(v).replace(/"/g, "")}"`).join(",")}}`;
  return {
    inc(name, labels = {}, n = 1) { const k = key(name, labels); counters.set(k, (counters.get(k) ?? 0) + n); },
    observe(name, labels, seconds) {
      const k = key(name, labels);
      const s = sums.get(k) ?? { sum: 0, count: 0, name, labels };
      s.sum += seconds; s.count++;
      sums.set(k, s);
    },
    render() {
      const lines = [];
      for (const [k, v] of counters) lines.push(`${k} ${v}`);
      for (const s of sums.values()) {
        lines.push(`${key(`${s.name}_sum`, s.labels)} ${s.sum.toFixed(6)}`);
        lines.push(`${key(`${s.name}_count`, s.labels)} ${s.count}`);
      }
      return lines.sort().join("\n") + "\n";
    },
  };
}

/** traceparent を読む（不正・なしなら新しいトレース） */
export function parseTraceparent(value) {
  const m = /^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/.exec(String(value ?? "").trim());
  if (!m || /^0+$/.test(m[1])) return { traceId: hex(16), parentSpanId: null, sampled: true };
  return { traceId: m[1], parentSpanId: m[2], sampled: (parseInt(m[3], 16) & 1) === 1 };
}

export function createTracer({ endpoint, serviceName = "mule", fetchImpl = fetch, flushMs = 2000, log = () => {} }) {
  const queue = [];
  let timer = null;

  async function flush() {
    timer = null;
    if (!endpoint || queue.length === 0) return;
    const spans = queue.splice(0, queue.length);
    const body = {
      resourceSpans: [{
        resource: { attributes: [{ key: "service.name", value: { stringValue: serviceName } }] },
        scopeSpans: [{ scope: { name: "bank-mule-compat" }, spans }],
      }],
    };
    try {
      await fetchImpl(`${endpoint.replace(/\/$/, "")}/v1/traces`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal: AbortSignal.timeout(3000),
      });
    } catch (e) {
      log(JSON.stringify({ event: "otel-export-failed", error: String(e?.message ?? e) }));
    }
  }

  /** スパンを開始する。end(attrs, error) で終わる。kind: 2 = SERVER, 3 = CLIENT */
  function start(name, { traceId, parentSpanId, kind = 1, attributes = {} }) {
    const spanId = hex(8);
    const startNs = BigInt(Date.now()) * 1_000_000n;
    return {
      traceId, spanId,
      traceparent: `00-${traceId}-${spanId}-01`,
      end(extra = {}, error = null) {
        if (!endpoint) return;
        const attrs = { ...attributes, ...extra };
        queue.push({
          traceId, spanId, parentSpanId: parentSpanId ?? undefined, name, kind,
          startTimeUnixNano: startNs.toString(), endTimeUnixNano: (BigInt(Date.now()) * 1_000_000n).toString(),
          attributes: Object.entries(attrs).filter(([, v]) => v !== undefined && v !== null).map(([k, v]) => ({
            key: k, value: typeof v === "number" ? { intValue: String(v) } : { stringValue: String(v) },
          })),
          status: error ? { code: 2, message: String(error).slice(0, 200) } : { code: 1 },
        });
        if (!timer) timer = setTimeout(flush, flushMs);
      },
    };
  }

  return { start, flush, enabled: Boolean(endpoint) };
}

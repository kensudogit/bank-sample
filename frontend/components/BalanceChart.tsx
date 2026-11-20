"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { ErrorBox } from "@/components/Feedback";
import { useTransactions } from "@/lib/api";
import { formatMoney } from "@/lib/format";

const RANGES = [
  { key: "30", label: "30日", days: 30 },
  { key: "90", label: "90日", days: 90 },
  { key: "365", label: "1年", days: 365 },
] as const;

const HEIGHT = 220;
const PAD = { top: 16, right: 16, bottom: 28, left: 64 };

interface Point {
  t: number;
  balance: number;
}

function isoDate(d: Date): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** Rounded ticks for the y axis (3–5 lines). */
function niceTicks(min: number, max: number): number[] {
  if (max === min) return [min];
  const span = max - min;
  const step0 = span / 4;
  const mag = 10 ** Math.floor(Math.log10(step0));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= step0) ?? step0;
  const ticks: number[] = [];
  for (let v = Math.floor(min / step) * step; v <= max + step * 0.001; v += step) ticks.push(v);
  return ticks;
}

/** Axis label in 万 units with just enough decimals for the tick step (never two equal labels). */
function axisYen(v: number, step: number): string {
  if (Math.abs(v) < 1e4 || step < 100) return v.toLocaleString("ja-JP");
  const decimals = step % 1e4 === 0 ? 0 : step % 1e3 === 0 ? 1 : 2;
  return `${(v / 1e4).toLocaleString("ja-JP", { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}万`;
}

/** 残高推移（取引ごとの取引後残高をつないだ階段状の線）。 */
export function BalanceChart({ accountNo, currentBalance, currency = "JPY" }: { accountNo: string; currentBalance: number; currency?: string }) {
  const [range, setRange] = useState<(typeof RANGES)[number]>(RANGES[1]);
  const from = useMemo(() => {
    const d = new Date();
    d.setDate(d.getDate() - range.days);
    return isoDate(d);
  }, [range]);
  const { data, error } = useTransactions({ accountNo, from, page: 1, size: 200 });

  const wrapRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(640);
  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setWidth(Math.max(280, Math.round(e.contentRect.width))));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  // Hovered position: x in px and the balance that applies there (step-after)
  const [hover, setHover] = useState<{ x: number; idx: number } | null>(null);

  const points: Point[] = useMemo(() => {
    if (!data) return [];
    const start = new Date(`${from}T00:00:00`).getTime();
    const tx = [...data.items].reverse();   // oldest first
    const pts: Point[] = tx.map((x) => ({ t: new Date(x.createdAt).getTime(), balance: x.balanceAfter }));
    // Start at the range start with the opening balance, unless the range holds more than one page:
    // then the chart starts at the oldest transaction we have
    if (!data.hasNext) pts.unshift({ t: start, balance: data.openingBalance });
    pts.push({ t: Date.now(), balance: currentBalance });
    return pts;
  }, [data, from, currentBalance]);

  const innerW = width - PAD.left - PAD.right;
  const innerH = HEIGHT - PAD.top - PAD.bottom;
  const geo = useMemo(() => {
    if (points.length < 2) return null;
    const t0 = points[0].t;
    const t1 = points[points.length - 1].t;
    const vals = points.map((p) => p.balance);
    let lo = Math.min(...vals);
    let hi = Math.max(...vals);
    if (lo === hi) {
      lo = Math.max(0, lo - Math.max(1, lo * 0.1));
      hi = hi + Math.max(1, hi * 0.1);
    }
    const ticks = niceTicks(Math.max(0, lo - (hi - lo) * 0.1), hi + (hi - lo) * 0.1);
    const yMin = ticks[0];
    const yMax = ticks[ticks.length - 1];
    const x = (t: number) => PAD.left + ((t - t0) / Math.max(1, t1 - t0)) * innerW;
    const y = (v: number) => PAD.top + innerH - ((v - yMin) / Math.max(1, yMax - yMin)) * innerH;
    // Step-after: the balance stays flat until the next transaction
    let d = `M${x(points[0].t)},${y(points[0].balance)}`;
    for (let i = 1; i < points.length; i++) d += ` H${x(points[i].t)} V${y(points[i].balance)}`;
    const area = `${d} V${PAD.top + innerH} H${x(t0)} Z`;
    return { x, y, d, area, ticks, t0, t1 };
  }, [points, innerW, innerH]);

  const onMove = (e: React.PointerEvent<SVGRectElement>) => {
    if (!geo) return;
    const rect = (e.currentTarget.ownerSVGElement as SVGSVGElement).getBoundingClientRect();
    const px = e.clientX - rect.left;
    // The balance shown at a time is the last point at or before it (step-after)
    let idx = 0;
    for (let i = 0; i < points.length; i++) if (geo.x(points[i].t) <= px) idx = i;
    setHover({ x: Math.min(Math.max(px, PAD.left), PAD.left + innerW), idx });
  };

  const hp = hover !== null && geo ? points[hover.idx] : null;
  // Time under the cursor (linear between the first and last point)
  const hoverTime = hover && geo ? geo.t0 + ((hover.x - PAD.left) / Math.max(1, innerW)) * (geo.t1 - geo.t0) : 0;
  const fmtDate = (t: number) => new Date(t).toLocaleDateString("ja-JP", { month: "numeric", day: "numeric" });

  return (
    <section className="card chart-card">
      <div className="chart-head">
        <h2 style={{ margin: 0 }}>残高推移</h2>
        <div className="tabs" role="tablist" aria-label="期間" style={{ margin: 0 }}>
          {RANGES.map((r) => (
            <button key={r.key} type="button" role="tab" className="tab" aria-selected={range.key === r.key}
              onClick={() => { setRange(r); setHover(null); }}>
              {r.label}
            </button>
          ))}
        </div>
      </div>
      <ErrorBox error={error} />
      <div ref={wrapRef} className="chart" style={{ position: "relative" }}>
        {!geo ? (
          <div className="skeleton" aria-label="読み込み中"><div style={{ height: HEIGHT }} /></div>
        ) : (
          <>
            <svg width={width} height={HEIGHT} role="img"
              aria-label={`残高推移（${range.label}）: ${formatMoney(points[0].balance, currency)} から ${formatMoney(currentBalance, currency)}`}>
              <defs>
                <linearGradient id="balanceFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="var(--series-1)" stopOpacity="0.18" />
                  <stop offset="100%" stopColor="var(--series-1)" stopOpacity="0" />
                </linearGradient>
              </defs>
              <g className="grid">
                {geo.ticks.map((v) => (
                  <line key={v} x1={PAD.left} x2={width - PAD.right} y1={geo.y(v)} y2={geo.y(v)} />
                ))}
              </g>
              <g className="axis">
                {geo.ticks.map((v) => (
                  <text key={v} x={PAD.left - 10} y={geo.y(v) + 4} textAnchor="end">
                    {currency === "JPY"
                      ? axisYen(v, geo.ticks.length > 1 ? geo.ticks[1] - geo.ticks[0] : 1)
                      : (v / 100).toLocaleString("ja-JP", { maximumFractionDigits: 2 })}
                  </text>
                ))}
                <text x={PAD.left} y={HEIGHT - 8}>{fmtDate(geo.t0)}</text>
                <text x={width - PAD.right} y={HEIGHT - 8} textAnchor="end">今日</text>
              </g>
              <path className="area" d={geo.area} />
              <path className="line" d={geo.d} />
              {hp && (
                <>
                  <line className="cursor" x1={hover!.x} x2={hover!.x} y1={PAD.top} y2={PAD.top + innerH} />
                  <circle className="dot" cx={hover!.x} cy={geo.y(hp.balance)} r={5} />
                </>
              )}
              {/* Hit area larger than the line for easy hovering */}
              <rect x={PAD.left} y={PAD.top} width={innerW} height={innerH} fill="transparent"
                onPointerMove={onMove} onPointerDown={onMove} onPointerLeave={() => setHover(null)} />
            </svg>
            {hp && geo && (
              <div className="chart-tooltip"
                style={{ left: Math.min(Math.max(hover!.x, 70), width - 70), top: geo.y(hp.balance) }}>
                <span className="muted">
                  {hover!.x >= PAD.left + innerW - 1
                    ? "現在"
                    : `${new Date(hoverTime).toLocaleDateString("ja-JP", { month: "numeric", day: "numeric" })} 時点`}
                </span>
                <strong>{formatMoney(hp.balance, currency)}</strong>
              </div>
            )}
          </>
        )}
      </div>
      {data?.hasNext && (
        <p className="hint" style={{ margin: "8px 0 0" }}>期間内の取引が多いため、直近200件の範囲を表示しています。</p>
      )}
    </section>
  );
}

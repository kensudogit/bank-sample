"use client";

import { useMemo, useRef, useState } from "react";
import { formatYen } from "@/lib/format";

/**
 * Daily trend of up to two yen series on ONE shared axis (never a dual axis).
 * Legend above, end-of-line direct labels, crosshair + tooltip on hover, and a table view for screen readers.
 */
export interface TrendSeries {
  label: string;
  values: number[];
}

const WIDTH = 720;
const HEIGHT = 240;
const PAD = { top: 16, right: 84, bottom: 28, left: 72 };

function niceTicks(min: number, max: number): number[] {
  if (max === min) return [min];
  const step0 = (max - min) / 4;
  const mag = 10 ** Math.floor(Math.log10(step0));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= step0) ?? step0;
  const ticks: number[] = [];
  for (let v = Math.floor(min / step) * step; v <= max + step * 0.001; v += step) ticks.push(v);
  return ticks;
}

/** 万円 / 億円 axis labels */
function axisYen(v: number): string {
  if (Math.abs(v) >= 1e8) return `${(v / 1e8).toLocaleString("ja-JP", { maximumFractionDigits: 2 })}億`;
  if (Math.abs(v) >= 1e4) return `${(v / 1e4).toLocaleString("ja-JP", { maximumFractionDigits: 1 })}万`;
  return v.toLocaleString("ja-JP");
}

export function TrendChart({ dates, series, title }: { dates: string[]; series: TrendSeries[]; title: string }) {
  const wrap = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<number | null>(null);

  const geo = useMemo(() => {
    const all = series.flatMap((s) => s.values);
    const lo = Math.min(...all);
    const hi = Math.max(...all);
    const pad = (hi - lo) * 0.08 || Math.max(1, hi * 0.05);
    const ticks = niceTicks(Math.max(0, lo - pad), hi + pad);
    const min = ticks[0];
    const max = ticks[ticks.length - 1] === min ? min + 1 : ticks[ticks.length - 1];
    const innerW = WIDTH - PAD.left - PAD.right;
    const innerH = HEIGHT - PAD.top - PAD.bottom;
    const x = (i: number) => PAD.left + (dates.length <= 1 ? innerW / 2 : (i / (dates.length - 1)) * innerW);
    const y = (v: number) => PAD.top + innerH - ((v - min) / (max - min)) * innerH;
    return { ticks, x, y, innerW };
  }, [dates, series]);

  if (dates.length === 0) return <p className="muted">取込済みのデータがありません。</p>;

  const onMove = (e: React.PointerEvent<SVGSVGElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const px = ((e.clientX - rect.left) / rect.width) * WIDTH;
    const i = dates.length <= 1 ? 0 : Math.round(((px - PAD.left) / geo.innerW) * (dates.length - 1));
    setHover(Math.min(dates.length - 1, Math.max(0, i)));
  };

  // Show only the first, middle and last date on the x axis (no crowding)
  const xLabels = Array.from(new Set([0, Math.floor((dates.length - 1) / 2), dates.length - 1]));
  const last = dates.length - 1;

  return (
    <div className="chart chart-card trend" ref={wrap}>
      <div className="chart-head">
        <h3 style={{ margin: 0 }}>{title}</h3>
        <div className="legend" aria-hidden="true">
          {series.map((s, i) => (
            <span key={s.label}><i className={`s${i + 1}`} />{s.label}</span>
          ))}
        </div>
      </div>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={`${title}（${dates[0]} 〜 ${dates[last]}）`}
        onPointerMove={onMove} onPointerLeave={() => setHover(null)}>
        <g className="grid">
          {geo.ticks.map((t) => (
            <line key={t} x1={PAD.left} x2={WIDTH - PAD.right} y1={geo.y(t)} y2={geo.y(t)} />
          ))}
        </g>
        <g className="axis">
          {geo.ticks.map((t) => (
            <text key={t} x={PAD.left - 8} y={geo.y(t) + 4} textAnchor="end">{axisYen(t)}</text>
          ))}
          {xLabels.map((i) => (
            <text key={i} x={geo.x(i)} y={HEIGHT - 6} textAnchor="middle">{dates[i].slice(5).replace("-", "/")}</text>
          ))}
        </g>
        {series.map((s, si) => (
          <g key={s.label}>
            <path className={`line-${si + 1}`}
              d={s.values.map((v, i) => `${i === 0 ? "M" : "L"}${geo.x(i).toFixed(1)},${geo.y(v).toFixed(1)}`).join(" ")} />
            <text className="end-label" x={geo.x(last) + 8} y={geo.y(s.values[last]) + 4}>{s.label}</text>
          </g>
        ))}
        {hover !== null && (
          <g>
            <line className="cursor" x1={geo.x(hover)} x2={geo.x(hover)} y1={PAD.top} y2={HEIGHT - PAD.bottom} />
            {series.map((s, si) => (
              <circle key={s.label} className={`dot-${si + 1}`} cx={geo.x(hover)} cy={geo.y(s.values[hover])} r={4.5} />
            ))}
          </g>
        )}
      </svg>
      {hover !== null && (
        <div className="chart-tooltip"
          style={{ left: `${(geo.x(hover) / WIDTH) * 100}%`, top: `${(PAD.top / HEIGHT) * 100 + 18}%` }}>
          <span className="muted">{dates[hover]}</span>
          {series.map((s) => (
            <div key={s.label} className="row"><span>{s.label}</span><strong>{formatYen(s.values[hover])}</strong></div>
          ))}
        </div>
      )}
      <table className="sr-only">
        <caption>{title}</caption>
        <thead><tr><th>日付</th>{series.map((s) => <th key={s.label}>{s.label}</th>)}</tr></thead>
        <tbody>
          {dates.map((d, i) => (
            <tr key={d}><td>{d}</td>{series.map((s) => <td key={s.label}>{s.values[i]}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

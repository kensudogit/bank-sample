// AI アシスタントの回答（Markdown）を表示する小さな描画器。
// 対応：見出し（#）、段落、箇条書き（- / * / 1.）、表（| … | と区切り行）、引用（>）、区切り線、太字（**）、コード（`）。
// HTML としては解釈しない（React の要素だけを作るので、回答に HTML やスクリプトが含まれても文字として表示される）。
// リンクも作らない（モデルの出力から外部へ誘導しない）。

import type { ReactNode } from "react";

/** 行内の書式：**太字** と `コード` */
export function inline(text: string, keyPrefix = "i"): ReactNode[] {
  const out: ReactNode[] = [];
  const re = /(\*\*([^*]+)\*\*|`([^`]+)`)/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let n = 0;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) out.push(text.slice(last, m.index));
    if (m[2] !== undefined) out.push(<strong key={`${keyPrefix}-${n++}`}>{m[2]}</strong>);
    else out.push(<code key={`${keyPrefix}-${n++}`} className="mono">{m[3]}</code>);
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

const splitRow = (line: string) => line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((c) => c.trim());
const isTableSep = (line: string) => /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(line);
const isTableRow = (line: string) => /^\s*\|.*\|\s*$/.test(line);
const listItem = /^\s*(?:[-*・]|(\d+)[.)])\s+(.*)$/;

export function Markdown({ text }: { text: string }) {
  const lines = text.replace(/\r\n?/g, "\n").split("\n");
  const blocks: ReactNode[] = [];
  let i = 0;
  let k = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.trim() === "") { i++; continue; }

    // 表：見出し行の次が区切り行
    if (isTableRow(line) && i + 1 < lines.length && isTableSep(lines[i + 1])) {
      const head = splitRow(line);
      const rows: string[][] = [];
      i += 2;
      while (i < lines.length && isTableRow(lines[i])) rows.push(splitRow(lines[i++]));
      const key = k++;
      blocks.push(
        <div className="table-wrap" key={`t${key}`}>
          <table>
            <thead><tr>{head.map((h, j) => <th key={j}>{inline(h, `th${key}-${j}`)}</th>)}</tr></thead>
            <tbody>{rows.map((r, ri) => <tr key={ri}>{head.map((_, j) => <td key={j}>{inline(r[j] ?? "", `td${key}-${ri}-${j}`)}</td>)}</tr>)}</tbody>
          </table>
        </div>,
      );
      continue;
    }

    const h = /^(#{1,6})\s+(.*)$/.exec(line);
    if (h) {
      const key = k++;
      // 回答の中の見出しはページの見出しより小さくする
      blocks.push(<h4 key={`h${key}`} style={{ margin: "12px 0 4px" }}>{inline(h[2], `h${key}`)}</h4>);
      i++;
      continue;
    }

    if (/^\s*(-{3,}|\*{3,})\s*$/.test(line)) { blocks.push(<hr key={`r${k++}`} />); i++; continue; }

    if (/^\s*>/.test(line)) {
      const quote: string[] = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) quote.push(lines[i++].replace(/^\s*>\s?/, ""));
      const key = k++;
      blocks.push(<blockquote key={`q${key}`} className="muted" style={{ margin: "8px 0", paddingLeft: 12, borderLeft: "3px solid var(--border, #ccc)" }}>{inline(quote.join(" "), `q${key}`)}</blockquote>);
      continue;
    }

    const li = listItem.exec(line);
    if (li) {
      const ordered = li[1] !== undefined;
      const items: string[] = [];
      while (i < lines.length) {
        const m = listItem.exec(lines[i]);
        if (!m || (m[1] !== undefined) !== ordered) break;
        items.push(m[2]);
        i++;
      }
      const key = k++;
      const children = items.map((it, j) => <li key={j}>{inline(it, `l${key}-${j}`)}</li>);
      blocks.push(ordered ? <ol key={`o${key}`}>{children}</ol> : <ul key={`u${key}`}>{children}</ul>);
      continue;
    }

    // 段落：空行・ほかの書式の行まで
    const para: string[] = [];
    while (i < lines.length && lines[i].trim() !== "" && !isTableRow(lines[i]) && !/^(#{1,6})\s/.test(lines[i])
      && !listItem.test(lines[i]) && !/^\s*>/.test(lines[i])) {
      para.push(lines[i++]);
    }
    if (para.length === 0) { para.push(lines[i++]); }
    const key = k++;
    blocks.push(
      <p key={`p${key}`} style={{ margin: "6px 0" }}>
        {para.flatMap((p, j) => (j === 0 ? inline(p, `p${key}-${j}`) : [<br key={`br${key}-${j}`} />, ...inline(p, `p${key}-${j}`)]))}
      </p>,
    );
  }
  return <div className="markdown">{blocks}</div>;
}

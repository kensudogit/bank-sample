// JUnit XML（Gradle・Vitest・node --test の junit reporter）を、画面で使う形に読み替える。
// 依存を増やさないため、テスト結果に出てくる要素（testsuite・testcase・failure・error・skipped・system-out/err）
// だけを扱う小さなタグの読み取りで済ませている。

const ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };

export function decode(s) {
  return s.replace(/&(#x[0-9a-fA-F]+|#\d+|\w+);/g, (m, e) => {
    if (e[0] === "#") {
      const code = e[1] === "x" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : m;
    }
    return ENTITIES[e] ?? m;
  });
}

function attrs(s) {
  const out = {};
  for (const m of s.matchAll(/([\w:.-]+)\s*=\s*("([^"]*)"|'([^']*)')/g)) out[m[1]] = decode(m[3] ?? m[4] ?? "");
  return out;
}

/**
 * @returns {{ name: string, classname: string, suite: string, time: number,
 *            status: "passed"|"failed"|"skipped", message?: string, detail?: string }[]}
 */
export function parseJUnit(xml) {
  const cases = [];
  const suites = [];
  let current = null;
  let capture = null; // failure / error / skipped の本文を集める
  const re = /<!\[CDATA\[([\s\S]*?)\]\]>|<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<(\/?)([\w:-]+)((?:\s+[^>]*?)?)(\/?)>|([^<]+)/g;
  for (const m of xml.matchAll(re)) {
    const [, cdata, closing, tag, rawAttrs, selfClosing, text] = m;
    if (cdata !== undefined || text !== undefined) {
      if (capture) capture.text += cdata !== undefined ? cdata : decode(text);
      continue;
    }
    if (!tag) continue;
    if (tag === "testsuite") {
      if (closing) suites.pop();
      else if (!selfClosing) suites.push(attrs(rawAttrs).name ?? "");
      continue;
    }
    if (tag === "testcase") {
      if (closing) {
        if (current) cases.push(finish(current));
        current = null;
        continue;
      }
      const a = attrs(rawAttrs);
      current = {
        name: a.name ?? "",
        classname: a.classname ?? "",
        suite: suites.filter(Boolean).join(" › "),
        time: Number(a.time) || 0,
        status: "passed",
      };
      if (selfClosing) {
        cases.push(finish(current));
        current = null;
      }
      continue;
    }
    if (!current) continue;
    if (tag === "failure" || tag === "error" || tag === "skipped") {
      if (closing) {
        if (capture) current.detail = (current.detail ? current.detail + "\n" : "") + capture.text.trim();
        capture = null;
        continue;
      }
      const a = attrs(rawAttrs);
      if (tag === "skipped") {
        if (current.status !== "failed") current.status = "skipped";
      } else {
        current.status = "failed";
      }
      if (a.message) current.message = a.message;
      if (!selfClosing) capture = { text: "" };
    }
  }
  return cases;
}

function finish(c) {
  if (c.detail === "") delete c.detail;
  return c;
}

export function summarize(cases) {
  const t = { total: cases.length, passed: 0, failed: 0, skipped: 0, time: 0 };
  for (const c of cases) {
    t[c.status]++;
    t.time += c.time;
  }
  t.time = Math.round(t.time * 1000) / 1000;
  return t;
}

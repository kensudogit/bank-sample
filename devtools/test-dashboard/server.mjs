// テストの実行と結果の確認を画面から行う、開発用のローカルサーバー（依存なし）。
// 対象は Java（勘定系：Gradle + JUnit 5）と JavaScript（画面：Vitest、連携の Node.js サービス：node --test）のテスト。
//
//   node devtools/test-dashboard/server.mjs      → http://127.0.0.1:4100
//
// 安全のため：127.0.0.1 だけで待ち受け、Host・Origin を確かめ、実行できるのは下の SUITES に書いた
// 決まったコマンドだけ（画面から任意のコマンドは渡せない）。本番（Railway）には載せない。

import { createServer } from "node:http";
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseJUnit, summarize } from "./junit.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "../..");
const RUNS = join(HERE, ".runs");
const PORT = Number(process.env.TEST_DASHBOARD_PORT) || 4100;
const WIN = process.platform === "win32";
const NODE = process.execPath;
const KEEP_RUNS = 30;
const LOG_LINES = 2000;

mkdirSync(RUNS, { recursive: true });

/** 実行できるテスト一式。junit は結果の XML（ファイル、またはディレクトリ内の *.xml）。 */
const SUITES = [
  {
    id: "backend",
    name: "勘定系",
    lang: "Java",
    tool: "Gradle · JUnit 5",
    cwd: "backend",
    // Windows：NoDefaultCurrentDirectoryInExePath が設定された環境でも動くよう、gradlew.bat は絶対パスで呼ぶ。
    // chcp 65001 で出力を UTF-8 にそろえる（既定の CP932 のままだとログが文字化けする）
    command: () =>
      WIN
        ? ["cmd.exe", ["/d", "/s", "/c", `"chcp 65001 >nul & "${join(ROOT, "backend", "gradlew.bat")}" cleanTest test --console=plain"`], { windowsVerbatimArguments: true }]
        : ["./gradlew", ["cleanTest", "test", "--console=plain"]],
    // 前回の XML を読まないよう、実行前に消す（Gradle が起動できなかったときに古い結果を出さない）
    before: () => rmSync(join(ROOT, "backend/build/test-results/test"), { recursive: true, force: true }),
    junit: () => join(ROOT, "backend/build/test-results/test"),
  },
  {
    id: "frontend",
    name: "画面",
    lang: "TypeScript / React",
    tool: "Vitest · Testing Library",
    cwd: "frontend",
    requires: "frontend/node_modules/vitest/vitest.mjs",
    command: (out) => [NODE, ["node_modules/vitest/vitest.mjs", "run", "--reporter=default", "--reporter=junit", `--outputFile.junit=${out}`]],
    junit: (out) => out,
  },
  nodeSuite("mule-compat", "MuleSoft 互換", "integration/mule-compat"),
  nodeSuite("auth-server", "トークン発行", "integration/auth-server"),
  nodeSuite("agent", "AI アシスタント", "integration/agent", "integration/agent/node_modules/@anthropic-ai/sdk/package.json"),
];

function nodeSuite(id, name, cwd, requires) {
  return {
    id,
    name,
    lang: "JavaScript",
    tool: "node --test",
    cwd,
    requires,
    command: (out) => [
      NODE,
      ["--test", "--test-reporter=spec", "--test-reporter-destination=stdout", "--test-reporter=junit", `--test-reporter-destination=${out}`],
    ],
    junit: (out) => out,
  };
}

// ---- 実行の状態 ----------------------------------------------------------

let active = null; // { run, child, cancelled }
const clients = new Set();

function broadcast(event, data) {
  const msg = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of clients) res.write(msg);
}

const stripAnsi = (s) => s.replace(/\x1b\[[0-9;?]*[ -/]*[@-~]/g, "");

function newRunId() {
  const d = new Date();
  const p = (n, w = 2) => String(n).padStart(w, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}-${p(d.getMilliseconds(), 3)}`;
}

function saveRun(run) {
  writeFileSync(join(RUNS, `${run.id}.json`), JSON.stringify(run));
  const files = readdirSync(RUNS).filter((f) => f.endsWith(".json")).sort();
  for (const f of files.slice(0, Math.max(0, files.length - KEEP_RUNS))) rmSync(join(RUNS, f), { force: true });
}

function readXmlCases(path) {
  if (!existsSync(path)) return null;
  const files = path.endsWith(".xml")
    ? [path]
    : readdirSync(path).filter((f) => f.endsWith(".xml")).map((f) => join(path, f));
  return files.flatMap((f) => parseJUnit(readFileSync(f, "utf8")));
}

async function startRun(ids) {
  const run = {
    id: newRunId(),
    startedAt: new Date().toISOString(),
    finishedAt: null,
    status: "running",
    suites: ids.map((id) => {
      const s = SUITES.find((x) => x.id === id);
      return { id, name: s.name, lang: s.lang, tool: s.tool, status: "queued", exitCode: null, durationMs: 0, totals: null, cases: [], log: [] };
    }),
  };
  active = { run, child: null, cancelled: false };
  broadcast("run", publicRun(run));

  for (const r of run.suites) {
    if (active.cancelled) {
      r.status = "cancelled";
      continue;
    }
    await runSuite(run, r);
  }
  run.finishedAt = new Date().toISOString();
  run.status = active.cancelled
    ? "cancelled"
    : run.suites.some((s) => s.status === "failed" || s.status === "error")
      ? "failed"
      : "passed";
  saveRun(run);
  active = null;
  broadcast("run", publicRun(run));
}

function runSuite(run, r) {
  const suite = SUITES.find((x) => x.id === r.id);
  const out = join(RUNS, `${run.id}-${r.id}.xml`);
  const log = (line) => {
    r.log.push(line);
    if (r.log.length > LOG_LINES) r.log.splice(0, r.log.length - LOG_LINES);
    broadcast("log", { runId: run.id, suite: r.id, line });
  };
  r.status = "running";
  broadcast("suite", { runId: run.id, suite: publicSuite(r) });

  if (suite.requires && !existsSync(join(ROOT, suite.requires))) {
    r.status = "error";
    log(`依存パッケージがありません（${suite.requires}）。${suite.cwd} で npm ci を実行してください。`);
    broadcast("suite", { runId: run.id, suite: publicSuite(r) });
    return Promise.resolve();
  }

  const [cmd, args, extra = {}] = suite.command(out);
  suite.before?.();
  log(`$ ${[cmd === NODE ? "node" : cmd, ...args].join(" ")}   (${suite.cwd})`);
  const started = Date.now();

  return new Promise((done) => {
    const child = spawn(cmd, args, {
      cwd: join(ROOT, suite.cwd),
      env: { ...process.env, FORCE_COLOR: "0", NO_COLOR: "1", CI: "1" },
      windowsHide: true,
      ...extra,
    });
    active.child = child;
    let buf = "";
    const onData = (chunk) => {
      buf += chunk.toString("utf8");
      const lines = buf.split(/\r?\n/);
      buf = lines.pop();
      for (const l of lines) log(stripAnsi(l));
    };
    child.stdout.on("data", onData);
    child.stderr.on("data", onData);
    child.on("error", (e) => log(`起動できませんでした：${e.message}`));
    child.on("close", (code) => {
      if (buf) log(stripAnsi(buf));
      active.child = null;
      r.exitCode = code;
      r.durationMs = Date.now() - started;
      const cases = readXmlCases(suite.junit(out));
      rmSync(out, { force: true });
      if (cases) {
        r.cases = cases;
        r.totals = summarize(cases);
      }
      if (active.cancelled) r.status = "cancelled";
      else if (r.totals && r.totals.failed > 0) r.status = "failed";
      else if (code !== 0) r.status = r.totals && r.totals.total > 0 ? "failed" : "error";
      else r.status = "passed";
      broadcast("suite", { runId: run.id, suite: publicSuite(r) });
      done();
    });
  });
}

function cancelRun() {
  if (!active) return false;
  active.cancelled = true;
  const child = active.child;
  if (child && child.exitCode === null) {
    if (WIN) spawnSync("taskkill", ["/pid", String(child.pid), "/T", "/F"], { windowsHide: true });
    else child.kill("SIGTERM");
  }
  return true;
}

// 一覧やイベントでは、ケースとログを省いた要約だけを送る
function publicSuite(r) {
  const { cases, log, ...rest } = r;
  return rest;
}
function publicRun(run) {
  return { ...run, suites: run.suites.map(publicSuite) };
}

function listRuns() {
  return readdirSync(RUNS)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .reverse()
    .map((f) => {
      try {
        return publicRun(JSON.parse(readFileSync(join(RUNS, f), "utf8")));
      } catch {
        return null;
      }
    })
    .filter(Boolean);
}

function getRun(id) {
  if (active && active.run.id === id) return active.run;
  if (!/^[\d-]+$/.test(id)) return null;
  const f = join(RUNS, `${id}.json`);
  return existsSync(f) ? JSON.parse(readFileSync(f, "utf8")) : null;
}

// ---- HTTP -------------------------------------------------------------

const ALLOWED_HOSTS = new Set([`127.0.0.1:${PORT}`, `localhost:${PORT}`]);

function send(res, status, body, type = "application/json; charset=utf-8") {
  res.writeHead(status, { "Content-Type": type, "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" });
  res.end(typeof body === "string" ? body : JSON.stringify(body));
}

async function readJson(req) {
  let body = "";
  for await (const chunk of req) {
    body += chunk;
    if (body.length > 10_000) throw new Error("too large");
  }
  return body ? JSON.parse(body) : {};
}

const server = createServer(async (req, res) => {
  // DNS リバインディング・他サイトからの呼び出しを防ぐ
  if (!ALLOWED_HOSTS.has(req.headers.host ?? "")) return send(res, 403, { error: "forbidden host" });
  const origin = req.headers.origin;
  if (origin && !ALLOWED_HOSTS.has(origin.replace(/^https?:\/\//, ""))) return send(res, 403, { error: "forbidden origin" });

  const url = new URL(req.url, `http://${req.headers.host}`);
  const path = url.pathname;

  if (req.method === "GET" && path === "/") {
    return send(res, 200, readFileSync(join(HERE, "index.html"), "utf8"), "text/html; charset=utf-8");
  }
  if (req.method === "GET" && path === "/api/suites") {
    return send(res, 200, SUITES.map(({ id, name, lang, tool, cwd }) => ({ id, name, lang, tool, cwd })));
  }
  if (req.method === "GET" && path === "/api/runs") {
    return send(res, 200, { active: active ? publicRun(active.run) : null, runs: listRuns() });
  }
  const m = path.match(/^\/api\/runs\/([\w-]+)$/);
  if (req.method === "GET" && m) {
    const run = getRun(m[1]);
    return run ? send(res, 200, run) : send(res, 404, { error: "not found" });
  }
  if (req.method === "GET" && path === "/api/events") {
    res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store", Connection: "keep-alive" });
    res.write(": connected\n\n");
    clients.add(res);
    const ping = setInterval(() => res.write(": ping\n\n"), 15_000);
    req.on("close", () => {
      clearInterval(ping);
      clients.delete(res);
    });
    return;
  }
  if (req.method === "POST" && (path === "/api/run" || path === "/api/cancel")) {
    // 単純なフォーム送信では付けられないヘッダーを要求する（CSRF 対策）
    if (req.headers["x-requested-with"] !== "test-dashboard") return send(res, 403, { error: "missing header" });
    if (path === "/api/cancel") return send(res, cancelRun() ? 202 : 409, { ok: true });
    if (active) return send(res, 409, { error: "already running", runId: active.run.id });
    let body;
    try {
      body = await readJson(req);
    } catch {
      return send(res, 400, { error: "bad json" });
    }
    const ids = Array.isArray(body.suites) ? body.suites : [];
    const valid = SUITES.map((s) => s.id).filter((id) => ids.includes(id)); // 並びは SUITES の順
    if (valid.length === 0 || valid.length !== new Set(ids).size) return send(res, 400, { error: "unknown suite" });
    startRun(valid).catch((e) => {
      console.error(e);
      active = null;
    });
    return send(res, 202, { runId: active.run.id });
  }
  send(res, 404, { error: "not found" });
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`テストダッシュボード: http://127.0.0.1:${PORT}`);
});

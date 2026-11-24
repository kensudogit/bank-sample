// 勘定系（Liberty）API のクライアント（Mule の core-banking-sapi.xml と同じ動き）。
// - 勘定系へは Kong 経由で届く（Liberty は Kong の共有シークレットがない要求を拒否する）
// - サービスアカウントでログインしてセッション Cookie を保持し、401 なら 1 回だけログインし直して再実行する
// - 接続失敗・5xx は指数バックオフで再試行する（呼ぶ API はすべて冪等：入金は参照番号、請求は契約・引落日の一意制約、
//   相場は上書き、実行履歴は冪等キーで重複しない）。4xx は再試行しない（同じ入力では同じ結果になる）

/** 勘定系の業務エラー（入力を受け付けなかった）。呼び出し元へは 422 で返す */
export class CoreRejected extends Error {
  constructor(message, status = 422, code = null) { super(message); this.name = "CoreRejected"; this.status = status; this.code = code; }
}
/** 勘定系に接続できない・障害。呼び出し元へは 502 で返す */
export class CoreUnavailable extends Error {
  constructor(message) { super(message); this.name = "CoreUnavailable"; }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export function createCoreClient({
  baseUrl, user, password, timeoutMs = 15000, fetchImpl = fetch, retries = 2, backoffMs = 300,
  onCall = () => {},
}) {
  let cookie = "";

  async function call(path, init) {
    try {
      return await fetchImpl(`${baseUrl}${path}`, { ...init, signal: AbortSignal.timeout(timeoutMs) });
    } catch (e) {
      throw new CoreUnavailable(`勘定系 API に接続できません（${e?.name ?? "error"}）`);
    }
  }

  async function login() {
    if (!password) throw new CoreUnavailable("サービスアカウントのパスワード（MULE_CORE_PASSWORD）が設定されていません");
    const res = await call("/login", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest", Accept: "application/json" },
      body: JSON.stringify({ userId: user, password }),
    });
    if (!res.ok) throw new CoreUnavailable(`サービスアカウントでログインできません（HTTP ${res.status}）`);
    const setCookie = res.headers.get("set-cookie") ?? "";
    cookie = setCookie.split(";")[0];
  }

  // 1 回の呼び出し（401 なら 1 回だけログインし直す）
  async function once(method, path, body, requestId, traceparent) {
    if (!cookie) await login();
    const send = () => call(path, {
      method,
      headers: {
        Accept: "application/json", Cookie: cookie, "X-Request-ID": requestId ?? "", ...(traceparent ? { traceparent } : {}),
        ...(method === "POST" ? { "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest" } : {}),
      },
      body: method === "POST" ? JSON.stringify(body) : undefined,
    });
    let res = await send();
    if (res.status === 401) {
      await login();
      res = await send();
    }
    let json = null;
    try { json = await res.json(); } catch { /* JSON でない応答 */ }
    if (res.ok && json && !json.error) return json.data;
    if (res.status >= 400 && res.status < 500) {
      throw new CoreRejected(json?.error?.message ?? `勘定系が要求を受け付けませんでした（HTTP ${res.status}）`, res.status, json?.error?.code ?? null);
    }
    throw new CoreUnavailable(`勘定系 API でエラーが発生しました（HTTP ${res.status}）`);
  }

  // 障害（接続失敗・5xx）だけを再試行する
  async function withRetry(method, path, body, requestId, traceparent) {
    const started = Date.now();
    for (let attempt = 0; ; attempt++) {
      try {
        const data = await once(method, path, body, requestId, traceparent);
        onCall({ path, result: "ok", attempt, seconds: (Date.now() - started) / 1000 });
        return data;
      } catch (e) {
        const retryable = e instanceof CoreUnavailable;
        if (!retryable || attempt >= retries) {
          onCall({ path, result: retryable ? "unavailable" : "rejected", attempt, seconds: (Date.now() - started) / 1000 });
          throw e;
        }
        onCall({ path, result: "retry", attempt, seconds: 0 });
        await sleep(backoffMs * 4 ** attempt);
      }
    }
  }

  /** 更新系 API を呼び、勘定系の data を返す（traceparent で分散トレースを引き継ぐ） */
  const post = (path, body, requestId, traceparent) => withRetry("POST", path, body, requestId, traceparent);
  /** 参照系 API を呼び、勘定系の data を返す */
  const get = (path, requestId, traceparent) => withRetry("GET", path, undefined, requestId, traceparent);

  async function health() {
    try {
      const res = await fetchImpl(`${baseUrl}/health`, { signal: AbortSignal.timeout(3000) });
      return res.ok ? "UP" : "DOWN";
    } catch {
      return "DOWN";
    }
  }

  return { post, get, health };
}

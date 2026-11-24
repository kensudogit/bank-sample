// 勘定系（Liberty）API のクライアント（Mule の core-banking-sapi.xml と同じ動き）。
// サービスアカウントでログインしてセッション Cookie を保持し、401 なら 1 回だけログインし直して再実行する。

/** 勘定系の業務エラー（入力を受け付けなかった）。呼び出し元へは 422 で返す */
export class CoreRejected extends Error {
  constructor(message) { super(message); this.name = "CoreRejected"; }
}
/** 勘定系に接続できない・障害。呼び出し元へは 502 で返す */
export class CoreUnavailable extends Error {
  constructor(message) { super(message); this.name = "CoreUnavailable"; }
}

export function createCoreClient({ baseUrl, user, password, timeoutMs = 15000, fetchImpl = fetch }) {
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

  /** 更新系 API を呼び、勘定系の data を返す */
  async function post(path, body, requestId) {
    if (!cookie) await login();
    const send = () => call(path, {
      method: "POST",
      headers: {
        "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest", Accept: "application/json",
        Cookie: cookie, "X-Request-ID": requestId ?? "",
      },
      body: JSON.stringify(body),
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
      throw new CoreRejected(json?.error?.message ?? `勘定系が要求を受け付けませんでした（HTTP ${res.status}）`);
    }
    throw new CoreUnavailable(`勘定系 API でエラーが発生しました（HTTP ${res.status}）`);
  }

  async function health() {
    try {
      const res = await fetchImpl(`${baseUrl}/health`, { signal: AbortSignal.timeout(3000) });
      return res.ok ? "UP" : "DOWN";
    } catch {
      return "DOWN";
    }
  }

  return { post, health };
}

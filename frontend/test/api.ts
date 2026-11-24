// fetch のモック。/api/bank 以下のパスごとに応答（データ・関数・エラー）を登録し、
// 実際の lib/api.ts・lib/query.ts の処理を通したまま画面をテストする。

import { vi } from "vitest";

/** 登録できる応答：値はそのまま { data } で返す。関数ならリクエスト内容から応答を作る。 */
export type Handler = unknown | ((req: MockRequest) => unknown);

/** ハンドラーに渡すリクエスト情報 */
export interface MockRequest {
  path: string;
  query: URLSearchParams;
  method: string;
  body: any;
  headers: Record<string, string>;
}

/** エラー応答を返したいときにハンドラーの値として使う。 */
export class ApiFailure {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly message: string,
    readonly fieldErrors: Record<string, string[]> = {},
  ) {}
}

/** 応答をそのまま返したいとき（{ data } で包まない・problem+json など）にハンドラーの値として使う。 */
export class RawResponse {
  constructor(
    readonly status: number,
    readonly body: unknown,
    readonly contentType = "application/json",
  ) {}
}

let routes = new Map<string, Handler>();
/** 受け付けたリクエストの記録（検証用） */
export const calls: MockRequest[] = [];

/** パス（例 "/accounts"）ごとの応答を登録する。既存の登録は上書きする。 */
export function mockApi(handlers: Record<string, Handler>): void {
  for (const [k, v] of Object.entries(handlers)) routes.set(k, v);
}

/** 指定パスへのリクエストの一覧 */
export function callsTo(path: string): MockRequest[] {
  return calls.filter((c) => c.path === path);
}

/** 最後の POST の本文 */
export function lastBody(path: string): any {
  const list = callsTo(path).filter((c) => c.method === "POST");
  return list[list.length - 1]?.body;
}

function json(status: number, payload: unknown): Response {
  return new Response(JSON.stringify(payload), { status, headers: { "Content-Type": "application/json" } });
}

function parseMaybeJson(s: string): unknown {
  try {
    return JSON.parse(s);
  } catch {
    return s;
  }
}

/** テストごとに登録と記録を消し、fetch を差し替え直す。 */
export function resetApi(): void {
  routes = new Map();
  calls.length = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const url = new URL(String(input), "http://localhost");
      const path = url.pathname.replace(/^\/api\/bank/, "");
      const req: MockRequest = {
        path,
        query: url.searchParams,
        method: init.method ?? "GET",
        body: typeof init.body === "string" ? parseMaybeJson(init.body) : init.body,
        headers: (init.headers ?? {}) as Record<string, string>,
      };
      calls.push(req);
      if (!routes.has(path)) {
        return json(404, { error: { code: "NOT_MOCKED", message: `not mocked: ${path}` } });
      }
      const h = routes.get(path);
      let result: unknown;
      try {
        result = typeof h === "function" ? await (h as (r: MockRequest) => unknown)(req) : h;
      } catch (e) {
        result = e;
      }
      if (result instanceof RawResponse) {
        return new Response(JSON.stringify(result.body), { status: result.status, headers: { "Content-Type": result.contentType } });
      }
      if (result instanceof ApiFailure) {
        return json(result.status, { error: { code: result.code, message: result.message, fieldErrors: result.fieldErrors } });
      }
      return json(200, { data: result ?? null });
    }),
  );
}

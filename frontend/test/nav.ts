// next/navigation の差し替え先。テストから現在のパス・パラメータを設定し、
// router.push / replace の呼び出しを検証できるようにする。

import { vi } from "vitest";

export const nav = {
  pathname: "/",
  params: {} as Record<string, string>,
  searchParams: new URLSearchParams(),
  router: {
    push: vi.fn(),
    replace: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    refresh: vi.fn(),
    prefetch: vi.fn(),
  },
};

/** 現在の画面（パス・動的パラメータ・クエリ文字列）を設定する。 */
export function setNav(pathname: string, params: Record<string, string> = {}, search = ""): void {
  nav.pathname = pathname;
  nav.params = params;
  nav.searchParams = new URLSearchParams(search);
}

/** テストごとに初期状態へ戻す。 */
export function resetNav(): void {
  setNav("/");
  for (const fn of Object.values(nav.router)) fn.mockReset();
}

// 全テスト共通の前準備。
// ・jest-dom のマッチャー（toBeInTheDocument など）を有効にする
// ・next/navigation と next/link を、App Router 無しの jsdom でも動く簡易版に差し替える
// ・テストごとに描画結果・API キャッシュ・fetch モック・画面遷移の記録を初期化する

import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, vi } from "vitest";
import { nav, resetNav } from "./nav";
import { resetApi } from "./api";

vi.mock("next/navigation", async () => {
  const { nav } = await import("./nav");
  return {
    useRouter: () => nav.router,
    usePathname: () => nav.pathname,
    useParams: () => nav.params,
    useSearchParams: () => nav.searchParams,
    redirect: (url: string) => nav.router.replace(url),
    notFound: () => {
      throw new Error("notFound");
    },
  };
});

vi.mock("next/link", async () => {
  const React = await import("react");
  type LinkProps = React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string | { pathname?: string }; prefetch?: boolean };
  const Link = React.forwardRef<HTMLAnchorElement, LinkProps>(function Link({ href, prefetch: _p, ...rest }, ref) {
    const url = typeof href === "string" ? href : (href as { pathname?: string }).pathname ?? "";
    return React.createElement("a", { ...rest, href: url, ref });
  });
  return { __esModule: true, default: Link };
});

// jsdom に無いブラウザ API の最小実装
if (!window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    }) as MediaQueryList;
}
window.scrollTo = (() => undefined) as typeof window.scrollTo;
if (!("ResizeObserver" in globalThis)) {
  (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}
if (!Element.prototype.setPointerCapture) {
  Element.prototype.setPointerCapture = () => undefined;
  Element.prototype.releasePointerCapture = () => undefined;
}
// jsdom には PointerEvent が無く、fireEvent.pointerMove の clientX が失われるため MouseEvent ベースで補う
if (!("PointerEvent" in globalThis)) {
  class PointerEventPolyfill extends MouseEvent {
    readonly pointerId: number;
    readonly pointerType: string;
    constructor(type: string, init: PointerEventInit = {}) {
      super(type, init);
      this.pointerId = init.pointerId ?? 1;
      this.pointerType = init.pointerType ?? "mouse";
    }
  }
  (globalThis as unknown as { PointerEvent: unknown }).PointerEvent = PointerEventPolyfill;
  (window as unknown as { PointerEvent: unknown }).PointerEvent = PointerEventPolyfill;
}
if (!URL.createObjectURL) URL.createObjectURL = () => "blob:mock";
if (!URL.revokeObjectURL) URL.revokeObjectURL = () => undefined;

beforeEach(() => {
  resetNav();
  resetApi();
  void nav;
});

afterEach(async () => {
  cleanup();
  const { clearCache } = await import("@/lib/query");
  clearCache();
  try {
    localStorage.clear();
  } catch {
    // ignore
  }
  delete document.documentElement.dataset.theme;
});

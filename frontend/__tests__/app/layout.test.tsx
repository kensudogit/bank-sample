// app/layout.tsx のテスト：メタデータ・テーマカラー、描画前にテーマを適用するスクリプト、AppShell で全画面を包む構成。
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/app/globals.css", () => ({}));
vi.mock("@/components/AuthProvider", () => ({
  AppShell: ({ children }: { children: React.ReactNode }) => <div data-testid="shell">{children}</div>,
}));

const { default: RootLayout, metadata, viewport } = await import("@/app/layout");

describe("RootLayout", () => {
  it("タイトル・説明・アイコンを定義する", () => {
    expect(metadata.title).toBe("永劫銀行 オンラインバンキング");
    expect(metadata.description).toContain("Struts 2");
    expect(metadata.icons).toEqual({ icon: "/logo.png", apple: "/logo.png" });
  });

  it("ライト／ダークのテーマカラーを OS 設定ごとに定義する", () => {
    expect(viewport.themeColor).toEqual([
      { media: "(prefers-color-scheme: light)", color: "#f4f5f8" },
      { media: "(prefers-color-scheme: dark)", color: "#0b0d12" },
    ]);
  });

  it("lang=ja の html と、AppShell で包んだ本文を出力する", () => {
    const html = renderToStaticMarkup(<RootLayout><p>page</p></RootLayout>);
    expect(html).toMatch(/^<html lang="ja">/);
    expect(html).toContain('<div data-testid="shell"><p>page</p></div>');
  });

  it("保存済みテーマを描画前に適用するスクリプトを head に埋め込む", () => {
    const html = renderToStaticMarkup(<RootLayout><p /></RootLayout>);
    const script = html.match(/<script>(.*?)<\/script>/)?.[1] ?? "";
    expect(script).toContain('localStorage.getItem("theme")');
    // スクリプトを実際に実行し、dark / light だけを data-theme に反映し、例外は握りつぶすことを確認する
    localStorage.setItem("theme", "dark");
    new Function(script.replace(/&quot;/g, '"').replace(/&amp;/g, "&"))();
    expect(document.documentElement.dataset.theme).toBe("dark");
    delete document.documentElement.dataset.theme;
    localStorage.setItem("theme", "evil");
    new Function(script.replace(/&quot;/g, '"').replace(/&amp;/g, "&"))();
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });
});

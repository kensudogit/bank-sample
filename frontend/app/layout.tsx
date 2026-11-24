// 全ページ共通のルートレイアウト。
// ページタイトル・アイコン・テーマカラーを定義し、AppShell（ログイン状態の管理とサイドバー）で全画面を包む。

import type { Metadata, Viewport } from "next";
import { Suspense } from "react";
import { AppShell } from "@/components/AuthProvider";
import "./globals.css";

/** ブラウザのタブに表示するタイトル・説明・アイコン */
export const metadata: Metadata = {
  title: "永劫銀行 オンラインバンキング",
  description: "Struts 2 / WebSphere Liberty / Oracle / Next.js bank sample",
  // frontend/public/logo.png (also used in the sidebar and login screen)
  icons: { icon: "/logo.png", apple: "/logo.png" },
};

/** OS のライト/ダーク設定に合わせたブラウザ UI のテーマカラー */
export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f4f5f8" },
    { media: "(prefers-color-scheme: dark)", color: "#0b0d12" },
  ],
};

// Applies the saved light/dark choice before first paint (no flash); storage errors fall back to the OS setting
// 描画前に保存済みのテーマを適用し、読み込み時の白黒の一瞬の切り替わり（ちらつき）を防ぐ
const themeScript = `try{var t=localStorage.getItem("theme");if(t==="dark"||t==="light")document.documentElement.dataset.theme=t}catch(e){}`;

/**
 * ルートレイアウト。<html> の suppressHydrationWarning は、themeScript が data-theme を
 * 書き換えることによる SSR との差分警告を抑えるため。Suspense は useSearchParams を使う子ページのため。
 */
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ja" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
      </head>
      <body>
        <Suspense>
          <AppShell>{children}</AppShell>
        </Suspense>
      </body>
    </html>
  );
}

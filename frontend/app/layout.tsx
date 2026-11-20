import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "サンプル銀行 オンラインバンキング",
  description: "Struts 2 / WebSphere Liberty / Oracle / Next.js bank sample",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ja">
      <body>
        <header className="header">
          <div className="container header-inner">
            <Link href="/" className="brand">
              サンプル銀行
            </Link>
            <nav className="nav">
              <Link href="/">口座一覧</Link>
              <Link href="/transfer">振込・振替</Link>
              <Link href="/customers">顧客・口座開設</Link>
              <Link href="/operations">業務処理</Link>
            </nav>
          </div>
        </header>
        <main className="container">{children}</main>
        <footer className="footer container">
          デモ用サンプル（架空データ）。認証機能はありません。本番利用不可。
        </footer>
      </body>
    </html>
  );
}

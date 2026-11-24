// Next.js の設定ファイル。
// ブラウザからの /api/bank/* を WebSphere Liberty の /bank/api/* へ転送（同一オリジン化して CORS を不要にする）し、
// 全ページに基本的なセキュリティヘッダーを付与する。

import type { NextConfig } from "next";

// Where the WebSphere Liberty (Struts 2) API lives.
// docker compose: http://liberty:9080 (build arg) / local `npm run dev`: http://localhost:9080
// API の転送先。Docker Compose ではビルド引数、ローカル開発では localhost:9080 を使う。
const apiOrigin = process.env.BANK_API_ORIGIN ?? "http://localhost:9080";

const nextConfig: NextConfig = {
  // standalone 出力：Docker イメージに必要最小限のサーバー一式だけを含めるため
  output: "standalone",
  poweredByHeader: false,
  // Same-origin proxy: the browser calls /api/bank/* and Next.js forwards to /bank/api/* (no CORS needed)
  async rewrites() {
    return [{ source: "/api/bank/:path*", destination: `${apiOrigin}/bank/api/:path*` }];
  },
  // クリックジャッキング・MIME スニッフィング・リファラー漏えいを防ぐヘッダーを全パスに付ける
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Referrer-Policy", value: "same-origin" },
        ],
      },
    ];
  },
};

export default nextConfig;

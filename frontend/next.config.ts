import type { NextConfig } from "next";

// Where the WebSphere Liberty (Struts 2) API lives.
// docker compose: http://liberty:9080 (build arg) / local `npm run dev`: http://localhost:9080
const apiOrigin = process.env.BANK_API_ORIGIN ?? "http://localhost:9080";

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  // Same-origin proxy: the browser calls /api/bank/* and Next.js forwards to /bank/api/* (no CORS needed)
  async rewrites() {
    return [{ source: "/api/bank/:path*", destination: `${apiOrigin}/bank/api/:path*` }];
  },
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

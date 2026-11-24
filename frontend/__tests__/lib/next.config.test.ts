// next.config.ts のテスト：API の転送先（rewrites）とセキュリティヘッダー。
import { afterEach, describe, expect, it, vi } from "vitest";

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe("next.config", () => {
  it("standalone 出力で X-Powered-By を出さない", async () => {
    const { default: config } = await import("@/next.config");
    expect(config.output).toBe("standalone");
    expect(config.poweredByHeader).toBe(false);
  });

  it("/api/bank/* を既定では localhost:9080 の /bank/api/* へ転送する", async () => {
    vi.stubEnv("BANK_API_ORIGIN", undefined as unknown as string);
    delete process.env.BANK_API_ORIGIN;
    const { default: config } = await import("@/next.config");
    const rewrites = await config.rewrites!();
    expect(rewrites).toEqual([{ source: "/api/bank/:path*", destination: "http://localhost:9080/bank/api/:path*" }]);
  });

  it("BANK_API_ORIGIN で転送先を変えられる", async () => {
    vi.stubEnv("BANK_API_ORIGIN", "http://liberty:9080");
    const { default: config } = await import("@/next.config");
    const rewrites = (await config.rewrites!()) as { destination: string }[];
    expect(rewrites[0].destination).toBe("http://liberty:9080/bank/api/:path*");
  });

  it("全パスに nosniff・DENY・same-origin のヘッダーを付ける", async () => {
    const { default: config } = await import("@/next.config");
    const [rule] = await config.headers!();
    expect(rule.source).toBe("/:path*");
    expect(Object.fromEntries(rule.headers.map((h) => [h.key, h.value]))).toEqual({
      "X-Content-Type-Options": "nosniff",
      "X-Frame-Options": "DENY",
      "Referrer-Policy": "same-origin",
    });
  });
});

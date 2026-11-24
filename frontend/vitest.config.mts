// Vitest の設定。jsdom 上で React コンポーネント・ページを描画してテストする。
// "@/..." の import は tsconfig の paths と同じくプロジェクト直下に解決する。
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: { alias: { "@": fileURLToPath(new URL("./", import.meta.url)) } },
  esbuild: { jsx: "automatic" },
  test: {
    environment: "jsdom",
    setupFiles: ["./test/setup.ts"],
    include: ["__tests__/**/*.test.{ts,tsx}"],
    css: false,
    restoreMocks: true,
  },
});

// lib/csv.ts のテスト：BOM 付き CSV の組み立て、エスケープ、数式インジェクション対策。
import { describe, expect, it, vi } from "vitest";
import { downloadCsv } from "@/lib/csv";

/** jsdom の Blob を文字列として読む（BOM を残すため ArrayBuffer で読み、ignoreBOM で復号する） */
function readBlob(b: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(new TextDecoder("utf-8", { ignoreBOM: true }).decode(r.result as ArrayBuffer));
    r.onerror = () => reject(r.error);
    r.readAsArrayBuffer(b);
  });
}

/** downloadCsv が作った Blob の中身とファイル名を取り出す。 */
async function capture(header: string[], rows: (string | number | null | undefined)[][]) {
  let blob: Blob | undefined;
  vi.spyOn(URL, "createObjectURL").mockImplementation((b) => {
    blob = b as Blob;
    return "blob:test";
  });
  const revoke = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
  downloadCsv("out.csv", header, rows);
  const text = await readBlob(blob!);
  return { text, click, revoke, blob: blob! };
}

describe("downloadCsv", () => {
  it("BOM 付き UTF-8・CRLF 区切りで出力し、Blob URL を解放する", async () => {
    const { text, click, revoke, blob } = await capture(["口座", "残高"], [["1000001", 1500]]);
    expect(blob.type).toContain("text/csv");
    expect(text.charCodeAt(0)).toBe(0xfeff);
    expect(text.slice(1)).toBe("口座,残高\r\n1000001,1500");
    expect(click).toHaveBeenCalledTimes(1);
    expect(revoke).toHaveBeenCalledWith("blob:test");
  });

  it("null・undefined・非有限数は空欄にし、数値文字列は数値のまま出す", async () => {
    const { text } = await capture(["a", "b", "c", "d"], [[null, undefined, Number.NaN, "-1200.5"]]);
    expect(text.split("\r\n")[1]).toBe(",,,-1200.5");
  });

  it("カンマ・改行・ダブルクォートを含む値はクォートする", async () => {
    const { text } = await capture(["x"], [['a,"b"'], ["1\n2"]]);
    const lines = text.slice(1).split("\r\n");
    expect(lines[1]).toBe('"a,""b"""');
    expect(lines[2]).toBe('"1\n2"');
  });

  it("先頭が = + - @ の文字列は ' を付けて数式として解釈させない", async () => {
    const { text } = await capture(["=SUM(A1)"], [["+cmd"], ["@x"], ["-abc"]]);
    const lines = text.slice(1).split("\r\n");
    expect(lines).toEqual([`"'=SUM(A1)"`, `"'+cmd"`, `"'@x"`, `"'-abc"`]);
  });
});

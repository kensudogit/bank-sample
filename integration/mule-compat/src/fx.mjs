// 為替相場の提供元のデータの検証（Mule の fx-rates-flow と同じ規則）。当行が扱う通貨（USD・EUR）だけを更新対象にする。

export const SUPPORTED = ["USD", "EUR"];

/** @returns {{provider:string, supported:object[], skipped:string[], errors:{line:number,message:string}[]}} */
export function parseFx(body) {
  const rates = Array.isArray(body?.rates) ? body.rates : [];
  const provider = typeof body?.provider === "string" ? body.provider : "";
  const errors = [
    ...(provider === "" || provider.length > 40 ? [{ line: 0, message: "provider が正しくありません" }] : []),
    ...(rates.length === 0 || rates.length > 20 ? [{ line: 0, message: "rates は 1〜20 件で指定してください" }] : []),
    ...rates.flatMap((r, i) => [
      ...(!/^[0-9]{1,6}(\.[0-9]{1,4})?$/.test(String(r?.mid ?? "")) ? [{ line: i + 1, message: "mid が正しくありません" }] : []),
      ...(!/^[0-9]{1,4}(\.[0-9]{1,4})?$/.test(String(r?.spread ?? "")) ? [{ line: i + 1, message: "spread が正しくありません" }] : []),
    ]),
  ];
  return {
    provider,
    supported: rates.filter((r) => SUPPORTED.includes(r?.currency)),
    skipped: rates.filter((r) => !SUPPORTED.includes(r?.currency)).map((r) => String(r?.currency ?? "")),
    errors,
  };
}

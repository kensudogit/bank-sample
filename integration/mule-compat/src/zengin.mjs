// 全銀フォーマット（総合振込・120 桁固定長）の検証・分解と、勘定系の被仕向入金 API の形式への変換。
// Mule アプリの dw/zengin-parse.dwl・dw/zengin-to-incoming.dwl と同じ規則（桁位置・検証・照会番号の作り方）。

/** 1 始まりの桁位置と長さで切り出す（文字数で数える。半角カナ 1 文字 = 1 桁） */
const cut = (s, from, len) => s.slice(from - 1, from - 1 + len);
const isNum = (s) => /^[0-9]+$/.test(s);

/**
 * 全銀データを検証して分解する。
 * @param {string} text ファイルの内容（文字列化済み）
 * @returns {{header: object|null, records: object[], trailer: object|null, totalAmount: number, errors: {line:number,message:string}[]}}
 */
export function parseZengin(text) {
  const numbered = text.split(/\r?\n/).filter((l) => l.length > 0)
    .map((l, i) => ({ line: i + 1, text: l, kind: l[0] ?? "" }));
  const lengthErrors = numbered.filter((r) => [...r.text].length !== 120)
    .map((r) => ({ line: r.line, message: `レコード長が 120 桁ではありません（${[...r.text].length} 桁）` }));
  const ok = (r) => r && [...r.text].length === 120;

  const h = numbered[0];
  const header = h && h.kind === "1" && ok(h) ? {
    kindCode: cut(h.text, 2, 2),
    requesterCode: cut(h.text, 5, 10),
    remitterNameKana: cut(h.text, 15, 40).trim(),
    tradeDate: cut(h.text, 55, 4),
    senderBankCode: cut(h.text, 59, 4),
  } : null;

  const records = numbered.filter((r) => r.kind === "2" && ok(r)).map((r) => ({
    line: r.line,
    bankCode: cut(r.text, 2, 4),
    branchCode: cut(r.text, 21, 3),
    destType: cut(r.text, 43, 1),
    accountNo: cut(r.text, 44, 7),
    nameKana: cut(r.text, 51, 30).trim(),
    amountText: cut(r.text, 81, 10),
    customerCode: cut(r.text, 92, 10).trim(),
  }));

  const trailers = numbered.filter((r) => r.kind === "8");
  const trailer = trailers.length === 1 && ok(trailers[0])
    ? { count: cut(trailers[0].text, 2, 6), total: cut(trailers[0].text, 8, 12) } : null;
  const end = numbered[numbered.length - 1];

  const recordErrors = records.flatMap((r) => [
    ...(!isNum(r.amountText) || Number(r.amountText) <= 0 ? [{ line: r.line, message: "金額が正しくありません" }] : []),
    ...(!isNum(r.accountNo) ? [{ line: r.line, message: "口座番号が数字 7 桁ではありません" }] : []),
    ...(!["1", "2", "4"].includes(r.destType) ? [{ line: r.line, message: "預金種目が正しくありません" }] : []),
    ...(r.nameKana === "" ? [{ line: r.line, message: "受取人名がありません" }] : []),
  ]);

  const structureErrors = [
    ...(header === null ? [{ line: 1, message: "先頭がヘッダーレコード（1）ではありません" }]
      : header.kindCode !== "21" ? [{ line: 1, message: "種別コードが総合振込（21）ではありません" }]
      : !isNum(header.senderBankCode) ? [{ line: 1, message: "仕向銀行コードが数字 4 桁ではありません" }] : []),
    ...(!end || end.kind !== "9" ? [{ line: numbered.length, message: "末尾がエンドレコード（9）ではありません" }] : []),
    ...(trailer === null ? [{ line: 0, message: "トレーラーレコード（8）がありません" }] : []),
    ...(records.length === 0 ? [{ line: 0, message: "データレコード（2）がありません" }] : []),
  ];

  const totalAmount = records.filter((r) => isNum(r.amountText)).reduce((s, r) => s + Number(r.amountText), 0);
  const totalErrors = trailer === null ? [] : [
    ...(!isNum(trailer.count) || Number(trailer.count) !== records.length
      ? [{ line: 0, message: `トレーラーの件数（${trailer.count}）とデータ件数（${records.length}）が一致しません` }] : []),
    ...(!isNum(trailer.total) || Number(trailer.total) !== totalAmount
      ? [{ line: 0, message: `トレーラーの合計金額（${trailer.total}）とデータの合計（${totalAmount}）が一致しません` }] : []),
  ];

  return { header, records, trailer, totalAmount, errors: [...lengthErrors, ...structureErrors, ...recordErrors, ...totalErrors] };
}

/**
 * 検証済みデータを勘定系の /remit-incoming の items に変換する。当行宛て以外は rejected に分ける。
 * 照会番号 = ZG-<仕向銀行>-<取組日 MMdd>-<顧客コード1（無ければ行番号）>
 */
export function toIncoming(z, ourBankCode = "9999") {
  const ref = (r) => `ZG-${z.header.senderBankCode}-${z.header.tradeDate}-${r.customerCode || String(r.line)}`;
  return {
    items: z.records.filter((r) => r.bankCode === ourBankCode).map((r) => ({
      line: r.line,
      request: {
        bankCode: z.header.senderBankCode,
        remitterNameKana: z.header.remitterNameKana,
        accountNo: r.accountNo,
        destType: r.destType,
        nameKana: r.nameKana,
        amount: String(Number(r.amountText)),
        referenceNo: ref(r),
      },
    })),
    rejected: z.records.filter((r) => r.bankCode !== ourBankCode).map((r) => ({
      line: r.line,
      accountNo: r.accountNo,
      amount: Number(r.amountText),
      referenceNo: ref(r),
      status: "REJECTED",
      note: `当行宛てではありません（被仕向銀行 ${r.bankCode}）`,
    })),
  };
}

/** 勘定系の結果と対象外をまとめ、行順の結果と集計を作る。 */
export function summarise(z, coreResults, rejected, runId) {
  const all = [...coreResults, ...rejected].sort((a, b) => a.line - b.line);
  const count = (s) => all.filter((r) => r.status === s).length;
  return {
    runId,
    senderBankCode: z.header.senderBankCode,
    remitterNameKana: z.header.remitterNameKana,
    tradeDate: z.header.tradeDate,
    records: z.records.length,
    totalAmount: z.totalAmount,
    summary: { credited: count("CREDITED"), held: count("HELD"), duplicate: count("DUPLICATE"), rejected: count("REJECTED") },
    results: all,
  };
}

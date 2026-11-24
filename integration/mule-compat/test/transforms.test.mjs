// 変換・検証のテスト。Mule アプリの MUnit と同じ fixtures（integration/mulesoft/bank-integration/src/test/resources/fixtures）を使う。
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { parseZengin, toIncoming, summarise } from "../src/zengin.mjs";
import { parseBilling, parseCsv } from "../src/billing.mjs";
import { parseFx } from "../src/fx.mjs";
import { decodeText } from "../src/server.mjs";

const fixture = (name) => readFileSync(new URL(`../../mulesoft/bank-integration/src/test/resources/fixtures/${name}`, import.meta.url));
const sample = fixture("zengin-incoming-sample.txt").toString("utf8");

test("全銀：正しいファイルを分解し、ヘッダー・明細・合計を取り出す", () => {
  const z = parseZengin(sample);
  assert.deepEqual(z.errors, []);
  assert.equal(z.header.senderBankCode, "0005");
  assert.equal(z.header.remitterNameKana, "ｶ)ﾃｽﾄｼﾖｳｼﾞ");
  assert.equal(z.header.tradeDate, "1124");
  assert.equal(z.records.length, 2);
  assert.deepEqual(z.records[0], {
    line: 2, bankCode: "9999", branchCode: "001", destType: "1", accountNo: "1000003",
    nameKana: "ｻﾄｳ ﾊﾅｺ", amountText: "0000120000", customerCode: "0000000001",
  });
  assert.equal(z.totalAmount, 125000);
});

test("全銀：Shift_JIS のファイルも charset で読める", () => {
  const text = decodeText(fixture("zengin-incoming-sample.sjis.txt"), "text/plain; charset=Shift_JIS");
  assert.equal(text, sample);
});

test("全銀：勘定系の形式に変換し、照会番号を作る。当行宛て以外は対象外", () => {
  const z = parseZengin(sample);
  z.records[1].bankCode = "0001";
  const m = toIncoming(z, "9999");
  assert.deepEqual(m.items[0].request, {
    bankCode: "0005", remitterNameKana: "ｶ)ﾃｽﾄｼﾖｳｼﾞ", accountNo: "1000003", destType: "1",
    nameKana: "ｻﾄｳ ﾊﾅｺ", amount: "120000", referenceNo: "ZG-0005-1124-0000000001",
  });
  assert.equal(m.rejected.length, 1);
  assert.equal(m.rejected[0].status, "REJECTED");
  const out = summarise(z, [{ line: 2, status: "CREDITED" }], m.rejected, "r1");
  assert.deepEqual(out.summary, { credited: 1, held: 0, duplicate: 0, rejected: 1 });
  assert.deepEqual(out.results.map((r) => r.line), [2, 3]);
});

test("全銀：顧客コードが空なら行番号で照会番号を作る", () => {
  const z = parseZengin(sample);
  z.records[0].customerCode = "";
  assert.equal(toIncoming(z).items[0].request.referenceNo, "ZG-0005-1124-2");
});

test("全銀：合計・件数・桁数・レコード構成の誤りを行番号付きで返す", () => {
  const badTotal = parseZengin(sample.replace("000000125000", "000000999999"));
  assert.match(badTotal.errors.map((e) => e.message).join(), /合計金額/);
  const badCount = parseZengin(sample.replace("8000002", "8000003"));
  assert.match(badCount.errors.map((e) => e.message).join(), /件数/);
  const lines = sample.split("\r\n");
  const shortLine = parseZengin([lines[0], lines[1].slice(0, 100), ...lines.slice(2)].join("\r\n"));
  assert.ok(shortLine.errors.some((e) => e.line === 2 && /120 桁/.test(e.message)));
  const noEnd = parseZengin(lines.slice(0, 4).join("\r\n"));
  assert.ok(noEnd.errors.some((e) => /エンドレコード/.test(e.message)));
  const notTransfer = parseZengin(sample.replace(/^121/, "111"));
  assert.ok(notTransfer.errors.some((e) => /総合振込/.test(e.message)));
  const badAmount = parseZengin(sample.replace("0000005000", "00000ABCDE"));
  assert.ok(badAmount.errors.some((e) => e.line === 3 && /金額/.test(e.message)));
  assert.ok(parseZengin("").errors.length > 0);
});

test("CSV：クォート・カンマ・改行・BOM を扱う", () => {
  assert.deepEqual(parseCsv('﻿a,"b,1","c""d"\r\n"x\ny",2,3\r\n\r\n'), [["a", "b,1", 'c"d'], ["x\ny", "2", "3"]]);
});

test("請求データ：日本語の見出し・カンマ付き金額・yyyyMMdd を正規化する", () => {
  const b = parseBilling(fixture("billing-sample.csv").toString("utf8"));
  assert.deepEqual(b.errors, []);
  assert.deepEqual(b.items, [
    { billerCode: "ELEC01", payerNumber: "E-0001", amount: "8000", dueDate: "2026-11-27" },
    { billerCode: "GAS01", payerNumber: "G-0001", amount: "4500", dueDate: "2026-11-27" },
  ]);
});

test("請求データ：英語の見出しも読め、不正な行は行番号付きで返す", () => {
  const b = parseBilling("billerCode,payerNumber,amount,dueDate\nelec,E-1,0,2026/11/27\n");
  assert.deepEqual(b.errors.map((e) => e.line), [2, 2, 2]);
  assert.match(parseBilling("収納企業コード,お客さま番号,金額,引落日\n").errors[0].message, /データ行がありません/);
});

test("為替相場：扱う通貨だけを更新対象にし、形式の誤りを返す", () => {
  const f = parseFx(JSON.parse(fixture("fx-rates-sample.json").toString("utf8")));
  assert.deepEqual(f.errors, []);
  assert.deepEqual(f.supported.map((r) => r.currency), ["USD", "EUR"]);
  assert.deepEqual(f.skipped, ["GBP"]);
  const bad = parseFx({ provider: "", rates: [{ currency: "USD", mid: "abc", spread: "-1" }] });
  assert.deepEqual(bad.errors.map((e) => e.message), ["provider が正しくありません", "mid が正しくありません", "spread が正しくありません"]);
  assert.equal(parseFx(null).errors.length, 2);
});

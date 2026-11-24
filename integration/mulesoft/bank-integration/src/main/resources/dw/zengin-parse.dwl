%dw 2.0
/*
 * 全銀フォーマット（総合振込・120 桁固定長）を読み、検証結果と明細に分解する。
 * 桁は文字数で数える（半角カナ 1 文字 = 1 桁。Shift_JIS のファイルは Content-Type の charset で文字列化済み）。
 *   1 ヘッダー : 種別(1) コード区分(2) 依頼人コード(10) 依頼人名(40) 取組日 MMdd(4) 仕向銀行(4) 銀行名(15) 支店(3) 支店名(15) 種目(1) 口座(7) ダミー(17)
 *   2 データ   : 被仕向銀行(4) 銀行名(15) 支店(3) 支店名(15) 手形交換所(4) 種目(1) 口座(7) 受取人(30) 金額(10) 新規(1) 顧客コード1(10) 顧客コード2(10) 振込区分(1) 識別(1) ダミー(7)
 *   8 トレーラー: 件数(6) 合計金額(12) ダミー(101)
 *   9 エンド
 * 出力: { header, records, trailer, errors }（errors が空でなければ取り込まない）
 */
output application/java
import * from dw::core::Strings

var lines = (payload as String) splitBy /\r?\n/ filter (sizeOf($) > 0)
// 1 始まりの桁位置と長さで切り出す
fun cut(s: String, from: Number, len: Number): String = s[from - 1 to from + len - 2]
fun num(s: String): Boolean = s matches /^[0-9]+$/

var numbered = lines map (l, i) -> { line: i + 1, text: l, kind: l[0] default "" }
var lengthErrors = numbered filter (sizeOf($.text) != 120)
  map { line: $.line, message: "レコード長が 120 桁ではありません（" ++ sizeOf($.text) ++ " 桁）" }
var headerRec = numbered[0]
var trailerRecs = numbered filter ($.kind == "8")
var endRec = numbered[-1]
var dataRecs = numbered filter ($.kind == "2")

var header = if (headerRec != null and headerRec.kind == "1" and sizeOf(headerRec.text) == 120) {
  kindCode: cut(headerRec.text, 2, 2),
  requesterCode: cut(headerRec.text, 5, 10),
  remitterNameKana: trim(cut(headerRec.text, 15, 40)),
  tradeDate: cut(headerRec.text, 55, 4),
  senderBankCode: cut(headerRec.text, 59, 4)
} else null

var records = dataRecs filter (sizeOf($.text) == 120) map (r) -> {
  line: r.line,
  bankCode: cut(r.text, 2, 4),
  branchCode: cut(r.text, 21, 3),
  destType: cut(r.text, 43, 1),
  accountNo: cut(r.text, 44, 7),
  nameKana: trim(cut(r.text, 51, 30)),
  amountText: cut(r.text, 81, 10),
  customerCode: trim(cut(r.text, 92, 10))
}

var trailer = if (sizeOf(trailerRecs) == 1 and sizeOf(trailerRecs[0].text) == 120)
  { count: cut(trailerRecs[0].text, 2, 6), total: cut(trailerRecs[0].text, 8, 12) } else null

var recordErrors = records flatMap (r) -> (
  (if (!num(r.amountText) or (r.amountText as Number) <= 0) [{ line: r.line, message: "金額が正しくありません" }] else [])
  ++ (if (!num(r.accountNo)) [{ line: r.line, message: "口座番号が数字 7 桁ではありません" }] else [])
  ++ (if (!(["1", "2", "4"] contains r.destType)) [{ line: r.line, message: "預金種目が正しくありません" }] else [])
  ++ (if (isEmpty(r.nameKana)) [{ line: r.line, message: "受取人名がありません" }] else [])
)

var structureErrors =
  (if (header == null) [{ line: 1, message: "先頭がヘッダーレコード（1）ではありません" }]
   else if (header.kindCode != "21") [{ line: 1, message: "種別コードが総合振込（21）ではありません" }]
   else if (!num(header.senderBankCode)) [{ line: 1, message: "仕向銀行コードが数字 4 桁ではありません" }]
   else [])
  ++ (if (endRec == null or endRec.kind != "9") [{ line: sizeOf(numbered), message: "末尾がエンドレコード（9）ではありません" }] else [])
  ++ (if (trailer == null) [{ line: 0, message: "トレーラーレコード（8）がありません" }] else [])
  ++ (if (isEmpty(records)) [{ line: 0, message: "データレコード（2）がありません" }] else [])

var amountSum = sum(records filter num($.amountText) map ($.amountText as Number))
var totalErrors = if (trailer == null) [] else (
  (if (!num(trailer.count) or (trailer.count as Number) != sizeOf(records))
     [{ line: 0, message: "トレーラーの件数（" ++ trailer.count ++ "）とデータ件数（" ++ sizeOf(records) ++ "）が一致しません" }] else [])
  ++ (if (!num(trailer.total) or (trailer.total as Number) != amountSum)
     [{ line: 0, message: "トレーラーの合計金額（" ++ trailer.total ++ "）とデータの合計（" ++ amountSum ++ "）が一致しません" }] else [])
)
---
{
  header: header,
  records: records,
  trailer: trailer,
  totalAmount: amountSum,
  errors: lengthErrors ++ structureErrors ++ recordErrors ++ totalErrors
}

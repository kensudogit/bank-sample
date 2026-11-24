%dw 2.0
/*
 * 検証済みの全銀データ（zengin-parse.dwl の出力 = vars.zengin）を、勘定系の被仕向入金 API（/remit-incoming）の
 * items 形式に変換する。当行（zengin.ourBankCode）宛て以外は rejected に分け、勘定系へは送らない。
 * 照会番号 referenceNo = ZG-<仕向銀行>-<取組日 MMdd>-<顧客コード1（無ければ行番号）> … 同じデータの再取込は勘定系が重複と判定する。
 */
output application/java
var z = vars.zengin
var ours = p('zengin.ourBankCode')
fun ref(r) = "ZG-" ++ z.header.senderBankCode ++ "-" ++ z.header.tradeDate ++ "-"
  ++ (if (isEmpty(r.customerCode)) (r.line as String) else r.customerCode)
---
{
  items: z.records filter ($.bankCode == ours) map (r) -> {
    line: r.line,
    request: {
      bankCode: z.header.senderBankCode,
      remitterNameKana: z.header.remitterNameKana,
      accountNo: r.accountNo,
      destType: r.destType,
      nameKana: r.nameKana,
      amount: r.amountText as Number as String,
      referenceNo: ref(r)
    }
  },
  rejected: z.records filter ($.bankCode != ours) map (r) -> {
    line: r.line,
    accountNo: r.accountNo,
    amount: r.amountText as Number,
    referenceNo: ref(r),
    status: "REJECTED",
    note: "当行宛てではありません（被仕向銀行 " ++ r.bankCode ++ "）"
  }
}

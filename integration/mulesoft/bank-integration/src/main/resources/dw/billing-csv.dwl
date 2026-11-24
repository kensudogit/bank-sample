%dw 2.0
/*
 * 収納企業の請求データ（CSV）を、勘定系の請求取込 API（/billing-import）の items に変換する。
 * 見出しは日本語（収納企業コード,お客さま番号,金額,引落日）または英語（billerCode,payerNumber,amount,dueDate）。
 * 金額のカンマを除き、引落日は yyyyMMdd も yyyy-MM-dd に直す。不正な行は errors に行番号付きで返す（1 件でもあれば取り込まない）。
 */
output application/java
var headerMap = {
  "収納企業コード": "billerCode", "お客さま番号": "payerNumber", "金額": "amount", "引落日": "dueDate",
  "billerCode": "billerCode", "payerNumber": "payerNumber", "amount": "amount", "dueDate": "dueDate"
}
var rows = payload as Array
fun normDate(d: String): String = if (d matches /^[0-9]{8}$/) d[0 to 3] ++ "-" ++ d[4 to 5] ++ "-" ++ d[6 to 7] else d
var items = rows map (r, i) -> do {
  var m = r mapObject (v, k) -> { (headerMap[k as String] default k as String): trim(v default "") }
  ---
  {
    line: i + 2,
    billerCode: m.billerCode default "",
    payerNumber: m.payerNumber default "",
    amount: (m.amount default "") replace "," with "",
    dueDate: normDate(m.dueDate default "")
  }
}
---
{
  items: items map ($ - "line"),
  errors: (if (isEmpty(items)) [{ line: 1, message: "データ行がありません" }] else [])
    ++ (if (sizeOf(items) > 1000) [{ line: 0, message: "1 回の取込は 1,000 行までです" }] else [])
    ++ (items flatMap (it) -> (
      (if (!(it.billerCode matches /^[A-Z0-9]{1,8}$/)) [{ line: it.line, message: "収納企業コードが正しくありません" }] else [])
      ++ (if (isEmpty(it.payerNumber) or sizeOf(it.payerNumber) > 20) [{ line: it.line, message: "お客さま番号が正しくありません" }] else [])
      ++ (if (!(it.amount matches /^[1-9][0-9]{0,9}$/)) [{ line: it.line, message: "金額が正しくありません" }] else [])
      ++ (if (!(it.dueDate matches /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/)) [{ line: it.line, message: "引落日が正しくありません" }] else [])
    ))
}

%dw 2.0
// エラー応答（RFC 9457 problem+json）。error.errorType と vars.problemStatus / vars.problemErrors から組み立てる。
// 勘定系の内部情報（スタックトレース・SQL 等）は返さない。
output application/json
var status = (vars.problemStatus default 500) as Number
var kind = error.errorType.identifier default "UNKNOWN"
---
{
  "type": "https://bank.example/problems/" ++ lower(kind replace "_" with "-"),
  title: status match {
    case 422 -> "入力データに誤りがあります"
    case 502 -> "勘定系システムに接続できません"
    else -> "連携処理でエラーが発生しました"
  },
  status: status,
  detail: if (status == 500) "時間をおいて再度お試しください。" else (error.description default ""),
  errors: vars.problemErrors default [],
  requestId: vars.requestId default ""
}

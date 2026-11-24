%dw 2.0
// 連携コンソールの行員トークン（HS256 の JWT。X-Bank-Operator-Token）を検証し、claims を返す。不正なら { error: 理由 }。
// 署名・発行者・宛先・期限に加えて、本文の SHA-256（vars.payloadSha256）と bodySha256 の一致、作成者と承認者が別人であることを確かめる。
// 署名鍵は p('operator.secret')（Secure Properties で渡す）。mule-compat/src/operator.mjs と同じ規則。
import * from dw::Crypto
import fromBase64, toBase64 from dw::core::Binaries
output application/java

var token = attributes.headers['x-bank-operator-token'] default ""
var parts = token splitBy "."
fun b64urlDecode(s: String): Binary = do {
  var std = (s replace "-" with "+") replace "_" with "/"
  var padded = std ++ ("=" * ((4 - (sizeOf(std) mod 4)) mod 4))
  ---
  fromBase64(padded)
}
fun b64url(b: Binary): String = ((toBase64(b) replace "+" with "-") replace "/" with "_") replace /=+$/ with ""
var nowSec = now() as Number { unit: "seconds" }
---
if (sizeOf(parts) != 3) { error: "malformed" }
else do {
  var expected = b64url(HMACBinary(p('operator.secret') as Binary { encoding: "UTF-8" }, (parts[0] ++ "." ++ parts[1]) as Binary { encoding: "UTF-8" }, "HmacSHA256"))
  var claims = read(b64urlDecode(parts[1]) as String { encoding: "UTF-8" }, "application/json")
  ---
  if (expected != parts[2]) { error: "bad signature" }
  else if (claims.iss != "bank-console" or claims.aud != "bank-integration") { error: "unexpected issuer or audience" }
  else if ((claims.exp default 0) < nowSec - 5) { error: "expired" }
  else if (claims.bodySha256 != vars.payloadSha256) { error: "body does not match the approved payload" }
  else if (isEmpty(claims.maker) or isEmpty(claims.approver) or claims.maker == claims.approver) { error: "maker and approver must differ" }
  else claims
}

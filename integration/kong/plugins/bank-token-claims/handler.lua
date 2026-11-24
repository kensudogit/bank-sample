-- bank-token-claims：jwt プラグインが検証済みのトークン（kong.ctx.shared.authenticated_jwt_token）の
-- iss・aud・roles を確かめる。満たさなければ 403（トークン自体がない・壊れていれば 401）。
local cjson = require "cjson.safe"

local BankTokenClaims = {
  -- jwt（1450）より後、acl（950）より前に動く
  PRIORITY = 1005,
  VERSION = "1.0.0",
}

local function b64url_decode(s)
  s = s:gsub("-", "+"):gsub("_", "/")
  local pad = #s % 4
  if pad > 0 then s = s .. string.rep("=", 4 - pad) end
  return ngx.decode_base64(s)
end

local function contains(list, value)
  if type(list) == "string" then return list == value end
  if type(list) ~= "table" then return false end
  for _, v in ipairs(list) do
    if v == value then return true end
  end
  return false
end

local function deny(status, message)
  return kong.response.exit(status, { message = message },
    { ["Content-Type"] = "application/json", ["WWW-Authenticate"] = 'Bearer error="invalid_token"' })
end

function BankTokenClaims:access(conf)
  local token = kong.ctx.shared.authenticated_jwt_token
  if not token then
    return deny(401, "Unauthorized")
  end
  local payload_part = token:match("^[^.]+%.([^.]+)%.")
  local raw = payload_part and b64url_decode(payload_part)
  local claims = raw and cjson.decode(raw)
  if type(claims) ~= "table" then
    return deny(401, "Unauthorized")
  end
  if claims.iss ~= conf.issuer then
    kong.log.notice("token rejected: unexpected issuer")
    return deny(403, "Forbidden")
  end
  local aud_ok = false
  for _, a in ipairs(conf.audiences) do
    if contains(claims.aud, a) then aud_ok = true break end
  end
  if not aud_ok then
    kong.log.notice("token rejected: unexpected audience")
    return deny(403, "Forbidden")
  end
  if #conf.required_roles > 0 then
    local roles = claims[conf.roles_claim]
    local role_ok = false
    for _, r in ipairs(conf.required_roles) do
      if contains(roles, r) then role_ok = true break end
    end
    if not role_ok then
      kong.log.notice("token rejected: missing role")
      return deny(403, "Forbidden")
    end
  end
end

return BankTokenClaims

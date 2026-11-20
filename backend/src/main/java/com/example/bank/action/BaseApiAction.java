package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.BankService;
import com.opensymphony.xwork2.ActionSupport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON API Action の基底クラス。
 * 成功時: {"data": ...}、失敗時: {"error": {"code", "message", "fieldErrors"?}}
 * 戻り値の result 名は struts.xml の global-results で HTTP ステータスに対応付ける。
 */
public abstract class BaseApiAction extends ActionSupport {
    protected final BankService service;
    private Map<String, Object> response;

    protected BaseApiAction(BankService service) { this.service = service; }

    /** JSON result の root。検証エラー（workflow interceptor が "input" を返した場合）もここで組み立てる。 */
    public Map<String, Object> getResponse() {
        if (response == null && hasErrors()) {
            Map<String, Object> error = error(ErrorCode.VALIDATION_ERROR, getText(ErrorCode.VALIDATION_ERROR.messageKey()));
            if (hasFieldErrors()) error.put("fieldErrors", getFieldErrors());
            if (hasActionErrors()) error.put("actionErrors", getActionErrors());
            response = wrap("error", error);
        }
        return response;
    }

    /** 想定外例外（global-exception-mappings → systemError）用の固定レスポンス。詳細は返さない。 */
    public Map<String, Object> getSystemErrorResponse() {
        return wrap("error", error(ErrorCode.SYSTEM_ERROR, getText(ErrorCode.SYSTEM_ERROR.messageKey())));
    }

    protected String ok(Object data) {
        response = wrap("data", data);
        return SUCCESS;
    }

    protected String fail(BankException e) {
        return fail(e.getCode(), e.getArgs());
    }

    /** エラーレスポンスを設定し、対応する result 名を返す（interceptor からも呼ばれる）。 */
    public String fail(ErrorCode code, String... args) {
        response = wrap("error", error(code, getText(code.messageKey(), args)));
        return code.resultName();
    }

    private static Map<String, Object> error(ErrorCode code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.name());
        error.put("message", message);
        return error;
    }

    private static Map<String, Object> wrap(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}

package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.LoginUser;
import com.example.bank.service.BankService;
import com.opensymphony.xwork2.ActionSupport;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON API Action の基底クラス。
 * 成功時: {"data": ...}、失敗時: {"error": {"code", "message", "fieldErrors"?}}
 * 戻り値の result 名は struts.xml の global-results で HTTP ステータスに対応付ける。
 * ログイン中の利用者は ApiGuardInterceptor が設定する。
 */
public abstract class BaseApiAction extends ActionSupport {
    protected final BankService service;
    private Map<String, Object> response;
    private LoginUser loginUser;
    private String errorCode;

    protected BaseApiAction(BankService service) { this.service = service; }

    /** JSON result の root。検証エラー（workflow interceptor が "input" を返した場合）もここで組み立てる。 */
    public Map<String, Object> getResponse() {
        if (response == null && hasErrors()) {
            Map<String, Object> error = error(ErrorCode.VALIDATION_ERROR, getText(ErrorCode.VALIDATION_ERROR.messageKey()));
            if (hasFieldErrors()) error.put("fieldErrors", getFieldErrors());
            if (hasActionErrors()) error.put("actionErrors", getActionErrors());
            response = wrap("error", error);
            errorCode = ErrorCode.VALIDATION_ERROR.name();
        }
        return response;
    }

    /** 想定外例外（global-exception-mappings → systemError）用の固定レスポンス。詳細は返さない。 */
    public Map<String, Object> getSystemErrorResponse() {
        errorCode = ErrorCode.SYSTEM_ERROR.name();
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
        errorCode = code.name();
        return code.resultName();
    }

    // ---- authentication / authorization ----

    /** ApiGuardInterceptor から設定される（未ログインの公開 API では null）。 */
    public void setLoginUser(LoginUser loginUser) { this.loginUser = loginUser; }

    protected LoginUser currentUser() { return loginUser; }

    /** 顧客ログインの場合は本人名義の口座のみ。存在を推測させないため他人の口座は「見つかりません」とする。 */
    protected void requireAccountAccess(String accountNo) {
        if (loginUser != null && loginUser.isCustomer()
                && !loginUser.getCustomerId().equals(service.accountOwner(accountNo))) {
            throw new BankException(ErrorCode.ACCOUNT_NOT_FOUND, accountNo);
        }
    }

    /** 顧客ログインの場合は本人の顧客情報のみ。 */
    protected void requireCustomerAccess(String customerId) {
        if (loginUser != null && loginUser.isCustomer() && !loginUser.getCustomerId().equals(customerId)) {
            throw new BankException(ErrorCode.CUSTOMER_NOT_FOUND, customerId);
        }
    }

    /** 顧客ログインなら本人の顧客番号、行員なら null（全顧客）。 */
    protected String customerScope() {
        return loginUser != null && loginUser.isCustomer() ? loginUser.getCustomerId() : null;
    }

    // ---- parameter parsing (invalid input -> 400 VALIDATION_ERROR) ----

    /** 円単位の 0 以上の整数（null・空は null）。 */
    protected static BigDecimal yen(String s) {
        String v = s == null ? null : s.trim().replace(",", "");
        if (v == null || v.isEmpty()) return null;
        if (!v.matches("^(0|[1-9][0-9]{0,11})$")) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return new BigDecimal(v);
    }

    /** 小数（金利・レートなど。null・空は null）。 */
    protected static BigDecimal decimal(String s) {
        String v = s == null ? null : s.trim();
        if (v == null || v.isEmpty()) return null;
        if (!v.matches("^[0-9]{1,9}(\\.[0-9]{1,6})?$")) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return new BigDecimal(v);
    }

    protected static long id(String s) {
        if (s == null || !s.trim().matches("^[1-9][0-9]{0,17}$")) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return Long.parseLong(s.trim());
    }

    protected static int integer(String s, int min, int max) {
        if (s == null || !s.trim().matches("^[0-9]{1,6}$")) throw new BankException(ErrorCode.VALIDATION_ERROR);
        int v = Integer.parseInt(s.trim());
        if (v < min || v > max) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return v;
    }

    protected static java.time.LocalDate date(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        java.time.LocalDate d = InputRules.toDate(s.trim());
        if (!InputRules.isReasonableDate(d)) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return d;
    }

    /** 1〜max 文字、制御文字なし（null・空は null）。 */
    protected static String text(String s, int max) {
        String v = InputRules.emptyToNull(s);
        if (v == null) return null;
        if (!InputRules.isText(v, max)) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return v;
    }

    protected static String required(String s, int max) {
        String v = text(s, max);
        if (v == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return v;
    }

    protected static String oneOf(String s, String... allowed) {
        for (String a : allowed) if (a.equals(s)) return s;
        throw new BankException(ErrorCode.VALIDATION_ERROR);
    }

    protected static String accountNo(String s) {
        String v = InputRules.trim(s);
        if (!InputRules.isAccountNo(v)) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return v;
    }

    protected static String key(String s) {
        String v = InputRules.trim(s);
        if (!InputRules.isIdempotencyKey(v)) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return v;
    }

    /** 実行者の利用者 ID（監査・職務分離用）。 */
    protected String who() {
        return loginUser == null ? null : loginUser.getUserId();
    }

    protected boolean isCustomerUser() {
        return loginUser != null && loginUser.isCustomer();
    }

    protected boolean isSupervisor() {
        return loginUser != null && LoginUser.SUPERVISOR.equals(loginUser.getRole());
    }

    /** 顧客ログインは本人の顧客番号に置き換える（他人の名義で申し込めないように）。 */
    protected String ownCustomerOr(String customerId) {
        return isCustomerUser() ? loginUser.getCustomerId() : customerId;
    }

    protected void requireOwner(String ownerCustomerId, ErrorCode notFound, String ref) {
        if (isCustomerUser() && !loginUser.getCustomerId().equals(ownerCustomerId)) throw new BankException(notFound, ref);
    }

    // ---- idempotency ----

    /** 更新系の冪等キー（再送時の結果再現と、監査ログ・明細の対応付けに使う）。キーのない API は null。 */
    public String getRequestKey() { return null; }

    /** 保存済みの前回の結果を返す（同じキーでの再送）。 */
    public void replay(Object data) {
        response = wrap("data", data);
        response.put("replayed", true);
        errorCode = null;
    }

    // ---- audit ----

    /** 監査ログの対象（口座番号など）。サブクラスで上書きする。 */
    public String getAuditTarget() { return null; }

    /** 未ログイン時の監査ログ上の利用者 ID（ログイン試行の ID など）。 */
    public String getAuditUserHint() { return null; }

    /** 失敗時のエラーコード（監査ログ用）。 */
    public String getAuditDetail() { return errorCode; }

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

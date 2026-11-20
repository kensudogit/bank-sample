package com.example.bank.domain;

/** API エラーコード。resultName は struts.xml の global-results（HTTP ステータス）に対応。 */
public enum ErrorCode {
    VALIDATION_ERROR("badRequest"),               // 400
    PASSWORD_POLICY("badRequest"),                // 400
    UNAUTHENTICATED("unauthorized"),              // 401
    AUTH_FAILED("unauthorized"),                  // 401
    ACCOUNT_LOCKED("unauthorized"),               // 401
    FORBIDDEN("forbidden"),                       // 403
    PASSWORD_CHANGE_REQUIRED("forbidden"),        // 403
    ACCOUNT_NOT_FOUND("notFound"),                // 404
    CUSTOMER_NOT_FOUND("notFound"),               // 404
    METHOD_NOT_ALLOWED("methodNotAllowed"),       // 405
    DUPLICATE_REQUEST("conflict"),                // 409
    REQUEST_IN_PROGRESS("conflict"),              // 409 同じキーの要求がまだ処理中
    IDEMPOTENCY_KEY_REUSED("unprocessable"),      // 422 同じキーで別の内容
    CUSTOMER_ALREADY_EXISTS("conflict"),          // 409
    LOGIN_ALREADY_ISSUED("conflict"),             // 409
    ACCOUNT_NOT_ACTIVE("unprocessable"),          // 422
    INSUFFICIENT_FUNDS("unprocessable"),          // 422
    SAME_ACCOUNT("unprocessable"),                // 422
    LIMIT_EXCEEDED("unprocessable"),              // 422
    DAILY_LIMIT_EXCEEDED("unprocessable"),        // 422
    NOT_OWN_ACCOUNT("unprocessable"),             // 422
    KYC_NOT_VERIFIED("unprocessable"),            // 422
    KYC_ALREADY_DECIDED("unprocessable"),         // 422
    SEGREGATION_OF_DUTIES("unprocessable"),       // 422
    AGE_RESTRICTION("unprocessable"),             // 422
    ACCOUNT_TYPE_LIMIT("unprocessable"),          // 422
    ACCOUNT_NOT_DORMANT("unprocessable"),         // 422
    INVALID_BASE_DATE("unprocessable"),           // 422
    ACCOUNT_TYPE_NOT_ALLOWED("unprocessable"),    // 422 この口座種別ではできない操作
    CURRENCY_MISMATCH("unprocessable"),           // 422
    NOTICE_REQUIRED("unprocessable"),             // 422 通知預金: 事前の通知が必要
    MINIMUM_AMOUNT("unprocessable"),              // 422 最低預入額未満
    CORPORATE_ONLY("unprocessable"),              // 422 法人のみ
    ACCOUNT_NOT_EMPTY("unprocessable"),           // 422 解約前に残高を 0 にする必要
    LOAN_OUTSTANDING("unprocessable"),            // 422 融資の返済口座など
    LOAN_NOT_FOUND("notFound"),                   // 404
    LOAN_STATE_INVALID("unprocessable"),          // 422
    LOAN_RULE_VIOLATION("unprocessable"),         // 422 総量規制・返済比率など
    CREDIT_LIMIT_EXCEEDED("unprocessable"),       // 422
    REMITTANCE_NOT_FOUND("notFound"),             // 404
    REMITTANCE_STATE_INVALID("unprocessable"),    // 422
    BANK_NOT_FOUND("unprocessable"),              // 422
    MANDATE_NOT_FOUND("notFound"),                // 404
    ALERT_NOT_FOUND("notFound"),                  // 404
    SYSTEM_ERROR("systemError");                  // 500

    private final String resultName;

    ErrorCode(String resultName) { this.resultName = resultName; }

    public String resultName() { return resultName; }

    /** messages.properties のキー */
    public String messageKey() { return "error." + name(); }
}

package com.example.bank.domain;

/** API エラーコード。resultName は struts.xml の global-results（HTTP ステータス）に対応。 */
public enum ErrorCode {
    VALIDATION_ERROR("badRequest"),            // 400
    ACCOUNT_NOT_FOUND("notFound"),             // 404
    CUSTOMER_NOT_FOUND("notFound"),            // 404
    METHOD_NOT_ALLOWED("methodNotAllowed"),    // 405
    DUPLICATE_REQUEST("conflict"),             // 409
    CUSTOMER_ALREADY_EXISTS("conflict"),       // 409
    ACCOUNT_NOT_ACTIVE("unprocessable"),       // 422
    INSUFFICIENT_FUNDS("unprocessable"),       // 422
    SAME_ACCOUNT("unprocessable"),             // 422
    LIMIT_EXCEEDED("unprocessable"),           // 422
    NOT_OWN_ACCOUNT("unprocessable"),          // 422
    KYC_NOT_VERIFIED("unprocessable"),         // 422
    KYC_ALREADY_DECIDED("unprocessable"),      // 422
    AGE_RESTRICTION("unprocessable"),          // 422
    ACCOUNT_TYPE_LIMIT("unprocessable"),       // 422
    ACCOUNT_NOT_DORMANT("unprocessable"),      // 422
    INVALID_BASE_DATE("unprocessable"),        // 422
    FORBIDDEN("forbidden"),                    // 403
    SYSTEM_ERROR("systemError");               // 500

    private final String resultName;

    ErrorCode(String resultName) { this.resultName = resultName; }

    public String resultName() { return resultName; }

    /** messages.properties のキー */
    public String messageKey() { return "error." + name(); }
}

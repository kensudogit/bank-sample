package com.example.bank.domain;

/** 業務エラー（取引はロールバックされる）。 */
public class BankException extends RuntimeException {
    private final ErrorCode code;
    private final String[] args;

    public BankException(ErrorCode code, String... args) {
        super(code.name());
        this.code = code;
        this.args = args == null ? new String[0] : args.clone();
    }

    public ErrorCode getCode() { return code; }

    public String[] getArgs() { return args.clone(); }
}

package com.example.bank.domain;

/** 業務エラー（取引はロールバックされる）。 */
public class BankException extends RuntimeException {
    private final ErrorCode code;
    private final String[] args;

    /**
     * @param code エラーコード（HTTP ステータスとメッセージキーを決める）
     * @param args メッセージの埋め込み引数
     */
    public BankException(ErrorCode code, String... args) {
        // ログで種別が分かるよう、例外メッセージにはコード名を入れる（画面向けの文言は messages.properties 側）
        super(code.name());
        this.code = code;
        this.args = args == null ? new String[0] : args.clone();
    }

    /** @return エラーコード */
    public ErrorCode getCode() { return code; }

    /** @return メッセージ引数（呼び出し側から配列を書き換えられないよう複製を返す） */
    public String[] getArgs() { return args.clone(); }
}

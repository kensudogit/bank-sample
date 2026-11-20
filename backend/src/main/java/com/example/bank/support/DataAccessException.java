package com.example.bank.support;

/** JDBC 例外のラッパー（システムエラーとして 500 を返す）。 */
public class DataAccessException extends RuntimeException {
    /**
     * @param message ログ用のメッセージ（利用者には表示しない）
     * @param cause   元の例外（SQLException など）
     */
    public DataAccessException(String message, Throwable cause) { super(message, cause); }
}

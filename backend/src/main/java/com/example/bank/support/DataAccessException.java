package com.example.bank.support;

/** JDBC 例外のラッパー（システムエラーとして 500 を返す）。 */
public class DataAccessException extends RuntimeException {
    public DataAccessException(String message, Throwable cause) { super(message, cause); }
}

package com.subflow.pg;

/** PG 호출 자체가 실패했을 때(설정 오류, 인증 실패 등). */
public class PgException extends RuntimeException {

    private final String code;

    public PgException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

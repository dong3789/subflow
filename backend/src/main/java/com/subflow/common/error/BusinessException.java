package com.subflow.common.error;

import org.springframework.http.HttpStatus;

/**
 * 업무 규칙 위반을 나타내는 기본 예외.
 *
 * <p>HTTP 응답 코드와 자체 오류 코드를 함께 들고 다녀, 서비스 계층이 예외만 던지면
 * {@link GlobalExceptionHandler}가 일관된 응답으로 바꿔 준다.
 */
public class BusinessException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public BusinessException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public BusinessException(String code, String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static BusinessException badRequest(String code, String message) {
        return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }
}

package com.subflow.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * 오류 응답 본문.
 *
 * @param timestamp  발생 시각(UTC)
 * @param status     HTTP 상태 코드
 * @param code       자체 오류 코드 (예: INVALID_STATE_TRANSITION)
 * @param message    사람이 읽을 수 있는 메시지
 * @param path       요청 경로
 * @param fieldErrors 요청 값 검증 실패 상세 (없으면 생략)
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldError> fieldErrors
) {

    public record FieldError(String field, String rejectedValue, String reason) {
    }

    public static ErrorResponse of(int status, String code, String message, String path) {
        return new ErrorResponse(Instant.now(), status, code, message, path, List.of());
    }

    public static ErrorResponse of(int status, String code, String message, String path, List<FieldError> errors) {
        return new ErrorResponse(Instant.now(), status, code, message, path, errors);
    }
}

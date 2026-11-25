package com.subflow.api.dto;

import java.time.Instant;

/**
 * 헬스 체크 응답.
 *
 * @param status    서비스 상태 (UP)
 * @param service   서비스 이름
 * @param version   빌드 버전
 * @param timestamp 응답 시각(UTC)
 */
public record HealthResponse(String status, String service, String version, Instant timestamp) {

    public static HealthResponse up(String service, String version) {
        return new HealthResponse("UP", service, version, Instant.now());
    }
}

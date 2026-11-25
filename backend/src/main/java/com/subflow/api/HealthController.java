package com.subflow.api;

import com.subflow.api.dto.HealthResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 생존 확인 API.
 *
 * <p>인프라 상태(DB·Redis)까지 보는 상세 점검은 Spring Boot Actuator(`/actuator/health`)가 담당하고,
 * 이 엔드포인트는 애플리케이션이 요청을 받을 수 있는지만 빠르게 알려 준다.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private static final String SERVICE_NAME = "subflow-backend";

    private final String version;

    public HealthController(@Value("${subflow.version:0.1.0-SNAPSHOT}") String version) {
        this.version = version;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return HealthResponse.up(SERVICE_NAME, version);
    }
}

package com.subflow;

import com.subflow.config.SubFlowProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * SubFlow 백엔드 진입점.
 *
 * <p>구독·정기결제 플랫폼의 1단계 스캐폴드로, 도메인 모델과 상태 머신, 스키마(Flyway),
 * 그리고 2단계에서 붙일 PG 연동·분산 락·Outbox 발행기의 착지점을 제공한다.
 */
@SpringBootApplication
@EnableConfigurationProperties(SubFlowProperties.class)
public class SubFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(SubFlowApplication.class, args);
    }
}

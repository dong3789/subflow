package com.subflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SubFlow 운영 파라미터.
 *
 * <p>1단계에서는 값만 정의해 두고, 2단계의 결제 실행 서비스(Dunning)·Outbox 발행기·정합성 점검이
 * 이 설정을 주입받아 사용한다.
 *
 * @param dunning   연체(Dunning) 재시도 정책
 * @param outbox    Transactional Outbox 발행 정책
 * @param lock      Redisson 분산 락 정책
 * @param webhook   PG 웹훅 수신 정책
 */
@ConfigurationProperties(prefix = "subflow")
public record SubFlowProperties(
        Dunning dunning,
        Outbox outbox,
        Lock lock,
        Webhook webhook
) {

    public SubFlowProperties {
        dunning = dunning != null ? dunning : Dunning.defaults();
        outbox = outbox != null ? outbox : Outbox.defaults();
        lock = lock != null ? lock : Lock.defaults();
        webhook = webhook != null ? webhook : Webhook.defaults();
    }

    /**
     * @param maxRetries        결제 실패 후 재시도 최대 횟수(도달 시 구독 만료)
     * @param retryInterval     재시도 간격
     */
    public record Dunning(int maxRetries, Duration retryInterval) {
        static Dunning defaults() {
            return new Dunning(3, Duration.ofHours(24));
        }
    }

    /**
     * @param batchSize       한 번에 발행할 이벤트 수
     * @param maxRetries      발행 실패 최대 재시도 횟수
     * @param retryBackoff    재시도 백오프
     */
    public record Outbox(int batchSize, int maxRetries, Duration retryBackoff) {
        static Outbox defaults() {
            return new Outbox(100, 5, Duration.ofSeconds(30));
        }
    }

    /**
     * @param keyPrefix      락 키 접두사
     * @param leaseTime      락 임대 시간 (watchdog 미사용 구간의 상한)
     * @param waitTime       락 획득 대기 시간
     */
    public record Lock(String keyPrefix, Duration leaseTime, Duration waitTime) {
        static Lock defaults() {
            return new Lock("subflow:lock:", Duration.ofSeconds(5), Duration.ofSeconds(3));
        }
    }

    /**
     * @param timestampTolerance 허용하는 PG 이벤트 시각 오차
     */
    public record Webhook(Duration timestampTolerance) {
        static Webhook defaults() {
            return new Webhook(Duration.ofMinutes(5));
        }
    }
}

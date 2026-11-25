package com.subflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SubFlow 운영 파라미터.
 *
 * <p>2단계 결제 코어 엔진(실행·Dunning·Outbox 발행·정합성 점검)이 이 값을 주입받아 쓴다.
 * 값이 비어 있으면 각 record의 기본값으로 채워진다.
 *
 * @param version   빌드 버전(헬스 응답)
 * @param dunning   연체(Dunning) 재시도 정책
 * @param billing   정기결제 실행 정책(중복 결제 가드 등)
 * @param outbox    Transactional Outbox 발행 정책
 * @param lock      분산 락 정책과 구현 선택
 * @param webhook   웹훅 서명·전송 정책
 * @param pg        PG 어댑터 선택
 * @param scheduler 배치 스케줄러 정책
 * @param apiKey    프로젝트 API Key 인증 정책
 */
@ConfigurationProperties(prefix = "subflow")
public record SubFlowProperties(
        String version,
        Dunning dunning,
        Billing billing,
        Outbox outbox,
        Lock lock,
        Webhook webhook,
        Pg pg,
        Scheduler scheduler,
        ApiKey apiKey
) {

    public SubFlowProperties {
        version = version != null ? version : "0.2.0-SNAPSHOT";
        dunning = dunning != null ? dunning : Dunning.defaults();
        billing = billing != null ? billing : Billing.defaults();
        outbox = outbox != null ? outbox : Outbox.defaults();
        lock = lock != null ? lock : Lock.defaults();
        webhook = webhook != null ? webhook : Webhook.defaults();
        pg = pg != null ? pg : Pg.defaults();
        scheduler = scheduler != null ? scheduler : Scheduler.defaults();
        apiKey = apiKey != null ? apiKey : ApiKey.defaults();
    }

    /**
     * @param maxRetries    결제 실패 후 재시도 최대 횟수(도달 시 구독 만료)
     * @param retryInterval 재시도 간격
     */
    public record Dunning(int maxRetries, Duration retryInterval) {
        static Dunning defaults() {
            return new Dunning(3, Duration.ofHours(24));
        }
    }

    /**
     * @param duplicateGuardWindow 같은 구독을 다시 청구하기까지 비워 둬야 하는 최소 간격
     * @param batchSize            배치 1회에서 처리할 구독 수
     */
    public record Billing(Duration duplicateGuardWindow, int batchSize) {
        static Billing defaults() {
            return new Billing(Duration.ofHours(24), 200);
        }
    }

    /**
     * @param batchSize    한 번에 발행할 이벤트 수
     * @param maxRetries   발행 실패 최대 재시도 횟수
     * @param retryBackoff 재시도 백오프(지수 백오프의 기준값)
     */
    public record Outbox(int batchSize, int maxRetries, Duration retryBackoff) {
        static Outbox defaults() {
            return new Outbox(100, 5, Duration.ofSeconds(30));
        }
    }

    /**
     * @param provider  {@code redis}(Redisson) 또는 {@code in-memory}(단일 인스턴스·테스트)
     * @param keyPrefix 락 키 접두사
     * @param leaseTime 락 임대 시간
     * @param waitTime  락 획득 대기 시간
     */
    public record Lock(String provider, String keyPrefix, Duration leaseTime, Duration waitTime) {
        static Lock defaults() {
            return new Lock("redis", "subflow:lock:", Duration.ofSeconds(10), Duration.ofSeconds(3));
        }

        public boolean usesRedis() {
            return provider == null || provider.equalsIgnoreCase("redis");
        }
    }

    /**
     * @param timestampTolerance 허용하는 PG 이벤트 시각 오차
     * @param transport          {@code http} 또는 {@code noop}
     */
    public record Webhook(Duration timestampTolerance, String transport) {
        static Webhook defaults() {
            return new Webhook(Duration.ofMinutes(5), "http");
        }
    }

    /**
     * @param provider 사용할 PG 어댑터 (현재는 {@code mock})
     */
    public record Pg(String provider) {
        static Pg defaults() {
            return new Pg("mock");
        }
    }

    /**
     * @param enabled            스케줄러 사용 여부(테스트에서는 끈다)
     * @param billingInterval    만기 구독 결제 배치 주기
     * @param dunningInterval    연체 재시도 배치 주기
     * @param outboxInterval     Outbox 발행 주기
     * @param cancellationInterval 기간 종료 해지 확정 주기
     */
    public record Scheduler(boolean enabled, Duration billingInterval, Duration dunningInterval,
                            Duration outboxInterval, Duration cancellationInterval) {
        static Scheduler defaults() {
            return new Scheduler(true, Duration.ofSeconds(60), Duration.ofMinutes(5),
                    Duration.ofSeconds(10), Duration.ofMinutes(5));
        }
    }

    /**
     * @param required   API Key 없이 들어온 요청을 거부할지
     * @param headerName API Key 헤더 이름
     */
    public record ApiKey(boolean required, String headerName) {
        static ApiKey defaults() {
            return new ApiKey(false, "X-Api-Key");
        }
    }
}

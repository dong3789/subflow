package com.subflow.domain.outbox;

import com.subflow.common.error.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Transactional Outbox 이벤트.
 *
 * <p>결제처럼 "DB 커밋"과 "외부 알림"을 함께 보장해야 하는 흐름에서, 도메인 변경과 같은 트랜잭션으로
 * 이 행을 남기고 별도 발행기가 비동기로 내보낸다. 이렇게 하면 커밋은 됐는데 이벤트가 유실되거나,
 * 이벤트는 나갔는데 커밋이 롤백되는 상황을 막을 수 있다.
 *
 * <p>발행기는 {@code status = PENDING and next_retry_at <= now} 행을 {@code id} 오름차순으로 가져가
 * {@code PUBLISHED}로 표시한다(2단계 구현).
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 이벤트가 발생한 애그리거트 종류 (예: Subscription, Payment). */
    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    /** 이벤트 종류 (예: PAYMENT_SUCCEEDED, SUBSCRIPTION_EXPIRED). */
    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    /** 이벤트 본문(JSON). 민감정보(카드번호, 빌링키)는 절대 담지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    private OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.payload = payload == null ? "{}" : payload;
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
    }

    public static OutboxEvent pending(String aggregateType, String aggregateId, String eventType, String payload) {
        return new OutboxEvent(aggregateType, aggregateId, eventType, payload);
    }

    public void markPublished(Instant publishedAt) {
        if (status != OutboxStatus.PENDING) {
            throw new InvalidStateTransitionException("발행 대기 상태가 아닙니다: " + status);
        }
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
        this.nextRetryAt = null;
        this.lastError = null;
    }

    /**
     * 발행 실패를 기록한다. 한도를 넘으면 FAILED로 확정하고, 그 전에는 재시도 시각을 잡는다.
     *
     * @return 더 이상 재시도하지 않으면(FAILED) true
     */
    public boolean markPublishFailed(String error, Instant nextRetryAt, int maxRetries) {
        if (status != OutboxStatus.PENDING) {
            throw new InvalidStateTransitionException("발행 대기 상태가 아닙니다: " + status);
        }
        if (maxRetries < 1) {
            throw new IllegalArgumentException("최대 재시도 횟수는 1 이상이어야 합니다: " + maxRetries);
        }
        this.retryCount++;
        this.lastError = error;
        if (this.retryCount >= maxRetries) {
            this.status = OutboxStatus.FAILED;
            this.nextRetryAt = null;
            return true;
        }
        this.nextRetryAt = nextRetryAt;
        return false;
    }

    public boolean isPending() {
        return status == OutboxStatus.PENDING;
    }
}

package com.subflow.domain.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 수신 웹훅 보관함(inbox) — 웹훅 멱등성의 방어선.
 *
 * <p>PG는 같은 이벤트를 여러 번 보낼 수 있다(재전송, 타임아웃 후 재시도). {@code (pg_provider, event_id)}
 * 유니크 제약으로 중복 수신을 1차 차단하고, 이미 처리된 이벤트는 다시 처리하지 않고 200을 돌려준다.
 * 처리 실패 시 FAILED로 남겨 재처리 배치가 다시 집어 갈 수 있게 한다.
 */
@Entity
@Table(name = "webhook_inbox",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webhook_inbox_provider_event",
                columnNames = {"pg_provider", "event_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookInbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pg_provider", nullable = false, length = 30)
    private String pgProvider;

    /** PG가 부여한 이벤트 ID. 재전송 시 동일하다. */
    @Column(name = "event_id", nullable = false, length = 120)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    /** 원본 페이로드(JSON). 재현·감사용으로 가공 없이 보관한다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WebhookInboxStatus status;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    private WebhookInbox(String pgProvider, String eventId, String eventType, String payload, Instant receivedAt) {
        this.pgProvider = Objects.requireNonNull(pgProvider, "pgProvider");
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.payload = payload == null ? "{}" : payload;
        this.status = WebhookInboxStatus.RECEIVED;
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    }

    public static WebhookInbox received(String pgProvider, String eventId, String eventType,
                                        String payload, Instant receivedAt) {
        return new WebhookInbox(pgProvider, eventId, eventType, payload, receivedAt);
    }

    public void markProcessed(Instant processedAt) {
        this.status = WebhookInboxStatus.PROCESSED;
        this.processedAt = processedAt;
        this.failureReason = null;
    }

    public void markFailed(String reason, Instant at) {
        this.status = WebhookInboxStatus.FAILED;
        this.failureReason = reason;
        this.processedAt = at;
    }

    public void markIgnored(String reason) {
        this.status = WebhookInboxStatus.IGNORED;
        this.failureReason = reason;
    }

    /** 이미 처리(또는 무시)할 필요가 없는 상태인지 — 중복 수신 시 조기 반환 판단. */
    public boolean isAlreadyHandled() {
        return status.isTerminal();
    }
}

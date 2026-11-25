package com.subflow.domain.payment;

import com.subflow.common.error.InvalidStateTransitionException;
import com.subflow.domain.subscription.Subscription;
import com.subflow.domain.support.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 원장(append-only에 가까운 기록).
 *
 * <p>한 번의 승인 시도 = 한 행. 멱등키({@code idempotencyKey})는 전역 유니크이며, 같은 키로는
 * 두 번 승인되지 않는다. 실패 후 재시도는 새 멱등키로 새 행을 만든다(재시도 이력은
 * {@link PaymentAttempt}에 남는다).
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    /** 승인 요청 멱등키. 전역 유니크. */
    @Column(name = "idempotency_key", nullable = false, length = 120, unique = true)
    private String idempotencyKey;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "pg_provider", length = 30)
    private String pgProvider;

    /** PG 승인 번호(거래 ID). */
    @Column(name = "pg_tx_id", length = 120)
    private String pgTxId;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "paid_at")
    private Instant paidAt;

    private Payment(Subscription subscription, String idempotencyKey, BigDecimal amount,
                    String currency, String pgProvider) {
        this.subscription = Objects.requireNonNull(subscription, "subscription");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("결제 금액은 0원 이상이어야 합니다: " + amount);
        }
        this.amount = amount;
        this.currency = currency == null ? "KRW" : currency.toUpperCase();
        this.pgProvider = pgProvider;
        this.status = PaymentStatus.PENDING;
    }

    /** 승인 요청 직전의 결제를 만든다(PENDING). */
    public static Payment request(Subscription subscription, String idempotencyKey,
                                  BigDecimal amount, String currency, String pgProvider) {
        return new Payment(subscription, idempotencyKey, amount, currency, pgProvider);
    }

    public void markSucceeded(String pgTxId, Instant paidAt) {
        ensureTransition(PaymentStatus.SUCCEEDED);
        this.status = PaymentStatus.SUCCEEDED;
        this.pgTxId = requireText(pgTxId, "pgTxId");
        this.paidAt = Objects.requireNonNull(paidAt, "paidAt");
        this.failureCode = null;
        this.failureReason = null;
    }

    public void markFailed(String failureCode, String failureReason) {
        ensureTransition(PaymentStatus.FAILED);
        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
    }

    public void markCanceled(String reason) {
        ensureTransition(PaymentStatus.CANCELED);
        this.status = PaymentStatus.CANCELED;
        this.failureReason = reason;
    }

    public boolean isPending() {
        return status == PaymentStatus.PENDING;
    }

    public boolean isSucceeded() {
        return status == PaymentStatus.SUCCEEDED;
    }

    private void ensureTransition(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw InvalidStateTransitionException.of("결제", status, target);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "는 비어 있을 수 없습니다.");
        }
        return value;
    }
}

package com.subflow.domain.payment;

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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 시도 이력(Dunning 기록).
 *
 * <ul>
 *   <li>{@code attemptNo}: 결제 건 안에서의 시도 순번(1부터). (payment_id, attempt_no) 유니크.</li>
 *   <li>{@code errorMessage}: PG가 준 실패 사유 원문. 운영 대응과 CS 응답의 근거가 된다.</li>
 * </ul>
 */
@Entity
@Table(name = "payment_attempts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_attempts_payment_attempt",
                columnNames = {"payment_id", "attempt_no"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentAttemptStatus status;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    private PaymentAttempt(Payment payment, int attemptNo, PaymentAttemptStatus status,
                           String errorMessage, Instant attemptedAt) {
        if (attemptNo < 1) {
            throw new IllegalArgumentException("시도 번호는 1 이상이어야 합니다: " + attemptNo);
        }
        this.payment = Objects.requireNonNull(payment, "payment");
        this.attemptNo = attemptNo;
        this.status = Objects.requireNonNull(status, "status");
        this.errorMessage = errorMessage;
        this.attemptedAt = Objects.requireNonNull(attemptedAt, "attemptedAt");
    }

    public static PaymentAttempt success(Payment payment, int attemptNo, Instant attemptedAt) {
        return new PaymentAttempt(payment, attemptNo, PaymentAttemptStatus.SUCCESS, null, attemptedAt);
    }

    public static PaymentAttempt failure(Payment payment, int attemptNo, String errorMessage, Instant attemptedAt) {
        return new PaymentAttempt(payment, attemptNo, PaymentAttemptStatus.FAILED, errorMessage, attemptedAt);
    }
}

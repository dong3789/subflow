package com.subflow.service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 결제 실행 1건의 결과.
 *
 * @param subscriptionId   구독 ID
 * @param paymentId        결제 원장 ID(결제를 만들지 않았으면 null)
 * @param paymentPublicId  결제 외부 식별자
 * @param status           결과 상태
 * @param message          사람이 읽을 설명
 * @param amount           청구 금액
 * @param pgTxId           PG 거래 ID
 * @param billingCycleCount 처리 후 누적 결제 회차
 * @param nextBillingAt    처리 후 다음 청구일
 */
public record BillingOutcome(
        Long subscriptionId,
        Long paymentId,
        String paymentPublicId,
        BillingResultStatus status,
        String message,
        BigDecimal amount,
        String pgTxId,
        int billingCycleCount,
        Instant nextBillingAt
) {

    public static BillingOutcome skipped(Long subscriptionId, BillingResultStatus status,
                                         String message, BigDecimal amount, int cycleCount, Instant nextBillingAt) {
        return new BillingOutcome(subscriptionId, null, null, status, message, amount, null,
                cycleCount, nextBillingAt);
    }
}

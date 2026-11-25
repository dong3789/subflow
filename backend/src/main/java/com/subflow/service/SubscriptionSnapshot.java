package com.subflow.service;

import com.subflow.domain.subscription.Subscription;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 구독 스냅샷 — 변경 이력(before/after)과 감사 로그에 남기는 값.
 *
 * <p>민감정보(카드·빌링키)는 담지 않는다. 변경 전후를 같은 모양으로 비교할 수 있게 필드를 고정한다.
 */
public record SubscriptionSnapshot(
        Long subscriptionId,
        String publicId,
        String status,
        Long planId,
        String planCode,
        String billingCycle,
        Integer anchorDay,
        int quantity,
        BigDecimal amount,
        String currency,
        int billingCycleCount,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        Instant nextBillingAt,
        Instant lastBilledAt,
        boolean cancelAtPeriodEnd,
        String cancelReason
) {

    public static SubscriptionSnapshot of(Subscription subscription) {
        return new SubscriptionSnapshot(
                subscription.getId(),
                subscription.getPublicId() == null ? null : subscription.getPublicId().toString(),
                subscription.getStatus().name(),
                subscription.getPlan().getId(),
                subscription.getPlan().getCode(),
                subscription.effectiveBillingCycle().name(),
                subscription.getAnchorDay(),
                subscription.getQuantity(),
                subscription.nextBillingAmount(),
                subscription.currency(),
                subscription.getBillingCycleCount(),
                subscription.getCurrentPeriodStart(),
                subscription.getCurrentPeriodEnd(),
                subscription.getNextBillingAt(),
                subscription.getLastBilledAt(),
                subscription.isCancelAtPeriodEnd(),
                subscription.getCancelReason());
    }
}

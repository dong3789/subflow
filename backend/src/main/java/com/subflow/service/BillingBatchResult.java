package com.subflow.service;

import java.time.Instant;
import java.util.List;

/**
 * 배치 1회 실행 결과.
 *
 * @param executedAt 실행 시각
 * @param outcomes   구독별 결과
 */
public record BillingBatchResult(Instant executedAt, List<BillingOutcome> outcomes) {

    public static BillingBatchResult of(Instant executedAt, List<BillingOutcome> outcomes) {
        return new BillingBatchResult(executedAt, List.copyOf(outcomes));
    }

    public long count(BillingResultStatus status) {
        return outcomes.stream().filter(outcome -> outcome.status() == status).count();
    }

    public long succeeded() {
        return count(BillingResultStatus.SUCCEEDED);
    }

    public long failed() {
        return outcomes.stream()
                .filter(outcome -> outcome.status() == BillingResultStatus.FAILED
                        || outcome.status() == BillingResultStatus.EXPIRED)
                .count();
    }

    public long skipped() {
        return outcomes.stream().filter(outcome -> outcome.status().isSkipped()).count();
    }

    public long pendingConfirmation() {
        return count(BillingResultStatus.PENDING_CONFIRMATION);
    }

    public int total() {
        return outcomes.size();
    }
}

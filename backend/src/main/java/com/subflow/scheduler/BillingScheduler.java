package com.subflow.scheduler;

import com.subflow.outbox.OutboxPublisher;
import com.subflow.outbox.PublishBatchResult;
import com.subflow.service.BillingBatchResult;
import com.subflow.service.BillingExecutionService;
import com.subflow.service.SubscriptionLifecycleService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정기결제 배치 스케줄러.
 *
 * <ul>
 *   <li><b>만기 결제</b>: {@code next_billing_at} 이 지난 구독을 청구한다(연체 구독은 Dunning 재시도로 처리).</li>
 *   <li><b>Dunning 재시도</b>: PAST_DUE 구독만 따로 돌려 재시도 간격을 지킨다.</li>
 *   <li><b>기간 종료 해지 확정</b>: 해지 예약 구독을 기간 종료 시점에 CANCELED 로 확정한다.</li>
 *   <li><b>Outbox 발행</b>: 적재된 이벤트를 프로젝트 웹훅으로 내보낸다.</li>
 * </ul>
 *
 * <p>각 배치는 서로 다른 주기로 돌고, 한 배치의 실패가 다른 배치를 멈추지 않도록 예외를 격리한다.
 */
@Component
@ConditionalOnProperty(name = "subflow.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class BillingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingScheduler.class);

    private final BillingExecutionService billingExecutionService;
    private final SubscriptionLifecycleService subscriptionLifecycleService;
    private final OutboxPublisher outboxPublisher;

    public BillingScheduler(BillingExecutionService billingExecutionService,
                            SubscriptionLifecycleService subscriptionLifecycleService,
                            OutboxPublisher outboxPublisher) {
        this.billingExecutionService = billingExecutionService;
        this.subscriptionLifecycleService = subscriptionLifecycleService;
        this.outboxPublisher = outboxPublisher;
    }

    /** 만기 구독 결제. */
    @Scheduled(fixedDelayString = "${subflow.scheduler.billing-interval:PT60S}",
            initialDelayString = "${subflow.scheduler.billing-interval:PT60S}")
    public void billDueSubscriptions() {
        try {
            BillingBatchResult result = billingExecutionService.runDueBilling(Instant.now());
            if (result.total() > 0) {
                log.info("[배치] 만기 결제 {}건 처리 — 성공 {} / 실패 {} / 건너뜀 {} / 미확정 {}",
                        result.total(), result.succeeded(), result.failed(), result.skipped(),
                        result.pendingConfirmation());
            }
        } catch (RuntimeException e) {
            log.error("[배치] 만기 결제 실행 실패", e);
        }
    }

    /** 연체(Dunning) 재시도. */
    @Scheduled(fixedDelayString = "${subflow.scheduler.dunning-interval:PT5M}",
            initialDelayString = "${subflow.scheduler.dunning-interval:PT5M}")
    public void retryPastDueSubscriptions() {
        try {
            BillingBatchResult result = billingExecutionService.runDunningRetry(Instant.now());
            if (result.total() > 0) {
                log.info("[배치] Dunning 재시도 {}건 — 성공 {} / 만료·실패 {}",
                        result.total(), result.succeeded(), result.failed());
            }
        } catch (RuntimeException e) {
            log.error("[배치] Dunning 재시도 실행 실패", e);
        }
    }

    /** 기간 종료 해지 확정. */
    @Scheduled(fixedDelayString = "${subflow.scheduler.cancellation-interval:PT5M}",
            initialDelayString = "${subflow.scheduler.cancellation-interval:PT5M}")
    public void applyPendingCancellations() {
        try {
            int applied = subscriptionLifecycleService.applyPendingCancellations(Instant.now()).size();
            if (applied > 0) {
                log.info("[배치] 기간 종료 해지 확정 {}건", applied);
            }
        } catch (RuntimeException e) {
            log.error("[배치] 해지 확정 실행 실패", e);
        }
    }

    /** Outbox 발행. */
    @Scheduled(fixedDelayString = "${subflow.scheduler.outbox-interval:PT10S}",
            initialDelayString = "${subflow.scheduler.outbox-interval:PT10S}")
    public void publishOutboxEvents() {
        try {
            PublishBatchResult result = outboxPublisher.publishPending(Instant.now());
            if (result.processed() > 0) {
                log.info("[배치] Outbox 발행 — 성공 {} / 건너뜀 {} / 재시도 {} / 실패 {}",
                        result.published(), result.skipped(), result.retried(), result.failed());
            }
        } catch (RuntimeException e) {
            log.error("[배치] Outbox 발행 실패", e);
        }
    }
}

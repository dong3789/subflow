package com.subflow.service;

import com.subflow.common.error.BusinessException;
import com.subflow.common.error.InvalidStateTransitionException;
import com.subflow.config.SubFlowProperties;
import com.subflow.domain.subscription.Subscription;
import com.subflow.domain.subscription.SubscriptionRepository;
import com.subflow.domain.subscription.SubscriptionStatus;
import com.subflow.lock.LockNotAcquiredException;
import com.subflow.lock.LockProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 정기결제 실행 오케스트레이터.
 *
 * <p>구독 단위 분산 락을 잡고 {@link BillingChargeProcessor}(트랜잭션 안쪽)를 호출한다.
 * 락 → 트랜잭션 커밋 → 락 해제 순서라, 다음 실행은 반드시 커밋된 상태를 본다.
 *
 * <p>배치는 한 구독의 실패가 나머지 구독 청구를 막지 않도록 건별로 격리한다.
 */
@Service
public class BillingExecutionService {

    private static final Logger log = LoggerFactory.getLogger(BillingExecutionService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final BillingChargeProcessor processor;
    private final LockProvider lockProvider;
    private final SubFlowProperties properties;

    public BillingExecutionService(SubscriptionRepository subscriptionRepository,
                                   BillingChargeProcessor processor,
                                   LockProvider lockProvider,
                                   SubFlowProperties properties) {
        this.subscriptionRepository = subscriptionRepository;
        this.processor = processor;
        this.lockProvider = lockProvider;
        this.properties = properties;
    }

    /** 구독 1건을 청구한다(락 → 트랜잭션). */
    public BillingOutcome charge(Long subscriptionId, BillingReason reason, Instant now) {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(now, "now");
        String lockKey = lockKey(subscriptionId);
        try {
            return lockProvider.withLock(lockKey, () -> processor.charge(subscriptionId, reason, now));
        } catch (LockNotAcquiredException e) {
            throw BusinessException.conflict("LOCK_NOT_ACQUIRED",
                    "같은 구독의 결제가 이미 진행 중입니다. 잠시 후 다시 시도해 주세요. key=" + lockKey);
        }
    }

    /** 당겨받기(즉시 갱신). 청구일과 무관하게 실행하되 중복 결제 가드는 그대로 적용된다. */
    public BillingOutcome renewNow(Long subscriptionId, Instant now) {
        return charge(subscriptionId, BillingReason.MANUAL_RENEWAL, now);
    }

    /**
     * 만기 구독 결제 배치. 연체(PAST_DUE) 구독은 Dunning 재시도 사유로 청구한다.
     */
    public BillingBatchResult runDueBilling(Instant now) {
        List<Subscription> due = subscriptionRepository.findDueSubscriptions(
                now, PageRequest.of(0, Math.max(1, properties.billing().batchSize())));
        List<BillingOutcome> outcomes = new ArrayList<>(due.size());
        for (Subscription subscription : due) {
            outcomes.add(chargeIsolated(subscription,
                    subscription.getStatus() == SubscriptionStatus.PAST_DUE
                            ? BillingReason.DUNNING_RETRY
                            : BillingReason.SCHEDULED,
                    now));
        }
        log.info("정기결제 배치: 대상 {}건, 성공 {}건", outcomes.size(),
                outcomes.stream().filter(outcome -> outcome.status().isSucceeded()).count());
        return BillingBatchResult.of(now, outcomes);
    }

    /** 연체 구독 Dunning 재시도만 따로 도는 배치(스케줄러에서 주기적으로 호출). */
    public BillingBatchResult runDunningRetry(Instant now) {
        List<Subscription> due = subscriptionRepository.findDuePastDue(
                now, PageRequest.of(0, Math.max(1, properties.billing().batchSize())));
        List<BillingOutcome> outcomes = new ArrayList<>(due.size());
        for (Subscription subscription : due) {
            outcomes.add(chargeIsolated(subscription, BillingReason.DUNNING_RETRY, now));
        }
        return BillingBatchResult.of(now, outcomes);
    }

    /** 한 건의 실패가 배치 전체를 멈추지 않게 격리한다. */
    private BillingOutcome chargeIsolated(Subscription subscription, BillingReason reason, Instant now) {
        try {
            return charge(subscription.getId(), reason, now);
        } catch (InvalidStateTransitionException e) {
            log.info("구독 {} 청구 건너뜀(상태 전이 불가): {}", subscription.getId(), e.getMessage());
            return BillingOutcome.skipped(subscription.getId(), BillingResultStatus.SKIPPED_NOT_BILLABLE,
                    e.getMessage(), null, subscription.getBillingCycleCount(), subscription.getNextBillingAt());
        } catch (BusinessException e) {
            log.info("구독 {} 청구 건너뜀: {}", subscription.getId(), e.getMessage());
            BillingResultStatus status = "DUPLICATE_BILLING_ATTEMPT".equals(e.getCode())
                    ? BillingResultStatus.SKIPPED_DUPLICATE
                    : BillingResultStatus.SKIPPED_NOT_BILLABLE;
            return BillingOutcome.skipped(subscription.getId(), status, e.getMessage(), null,
                    subscription.getBillingCycleCount(), subscription.getNextBillingAt());
        } catch (RuntimeException e) {
            log.error("구독 {} 청구 중 오류", subscription.getId(), e);
            return new BillingOutcome(subscription.getId(), null, null, BillingResultStatus.FAILED,
                    "청구 중 오류: " + e.getMessage(), null, null,
                    subscription.getBillingCycleCount(), subscription.getNextBillingAt());
        }
    }

    private String lockKey(Long subscriptionId) {
        return properties.lock().keyPrefix() + "sub:" + subscriptionId;
    }
}

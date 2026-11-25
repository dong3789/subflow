package com.subflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.subflow.common.error.DuplicateBillingAttemptException;
import com.subflow.domain.payment.PaymentStatus;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.subscription.Subscription;
import com.subflow.support.IntegrationTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 동시성 테스트 — 같은 구독에 두 개의 결제 요청이 동시에 들어와도 한 번만 결제되어야 한다.
 *
 * <p>방어선은 3중이다: 구독 단위 분산 락(테스트에서는 인메모리) → 비관적 락 → 멱등키 유니크.
 * 이 테스트는 그 조합이 실제 커밋 경계에서 중복 승인을 막는지 확인한다.
 */
class BillingConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("동시에 두 번 당겨받아도 결제는 1건만 승인된다")
    void concurrentRenewalsChargeOnce() throws Exception {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        Instant now = T0.plusSeconds(40L * 86400);

        List<BillingOutcome> succeeded = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Void> task = () -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    BillingOutcome outcome = billingService.renewNow(subscription.getId(), now);
                    synchronized (succeeded) {
                        succeeded.add(outcome);
                    }
                } catch (DuplicateBillingAttemptException e) {
                    synchronized (rejected) {
                        rejected.add(e.getCode());
                    }
                }
                return null;
            };
            Future<Void> first = pool.submit(task);
            Future<Void> second = pool.submit(task);
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded).as("승인된 요청은 1건").hasSize(1);
        assertThat(rejected).as("나머지는 중복 결제로 거절").hasSize(1);
        assertThat(paymentRepository.findBySubscriptionIdAndStatus(subscription.getId(), PaymentStatus.SUCCEEDED))
                .hasSize(1);
        assertThat(mockPgClient.approvedCount()).isEqualTo(1);
        assertThat(reload(subscription.getId()).getBillingCycleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 회차를 다시 청구해도 새 결제가 생기지 않는다")
    void replayReturnsExistingPayment() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);

        BillingOutcome first = billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);
        BillingOutcome replay = billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);

        assertThat(first.status()).isEqualTo(BillingResultStatus.SUCCEEDED);
        assertThat(replay.status())
                .isIn(BillingResultStatus.SKIPPED_NOT_DUE, BillingResultStatus.SKIPPED_DUPLICATE,
                        BillingResultStatus.REPLAYED);
        assertThat(paymentRepository.findBySubscriptionIdOrderByIdDesc(subscription.getId())).hasSize(1);
        assertThat(mockPgClient.approvedCount()).isEqualTo(1);
    }
}

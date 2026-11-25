package com.subflow.domain.subscription;

import static java.time.temporal.ChronoUnit.DAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subflow.common.error.InvalidStateTransitionException;
import com.subflow.domain.billingkey.BillingKey;
import com.subflow.domain.customer.Customer;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.plan.Plan;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 구독 상태 머신 단위 테스트.
 *
 * <p>DB 없이 도메인 객체만으로 검증한다. 다루는 것:
 * <ul>
 *   <li>체험/즉시결제 구독의 시작 상태와 청구일 계산</li>
 *   <li>상태 전이 매트릭스와 잘못된 전이 거부</li>
 *   <li>Dunning: 실패 누적 → 연체 → 재시도 한도 초과 시 만료</li>
 *   <li>연체 해소, 기간 종료 해지 예약, 즉시 해지, 일시정지/재개</li>
 * </ul>
 */
class SubscriptionStateMachineTest {

    /** 기준 시각: 2026-11-25T00:00:00Z (KST 09:00) */
    private static final Instant T0 = Instant.parse("2026-11-25T00:00:00Z");

    private Customer customer;
    private Plan monthlyPlan;
    private Plan trialPlan;
    private BillingKey billingKey;

    @BeforeEach
    void setUp() {
        customer = Customer.register("dong@example.com", "동동", "ext-dong-1");
        monthlyPlan = Plan.create("BASIC_MONTHLY", "베이직 월간", new BigDecimal("9900"), "KRW",
                BillingCycle.MONTHLY, 0, null);
        trialPlan = Plan.create("PRO_MONTHLY_TRIAL", "프로 월간(14일 체험)", new BigDecimal("19900"), "KRW",
                BillingCycle.MONTHLY, 14, "체험 후 자동 결제");
        billingKey = BillingKey.issue(customer, "TOSS", "billing-token-1", "1234");
    }

    private Subscription activeSubscription() {
        return Subscription.start(customer, monthlyPlan, billingKey, T0);
    }

    private Subscription trialSubscription() {
        return Subscription.start(customer, trialPlan, billingKey, T0);
    }

    @Nested
    @DisplayName("구독 시작")
    class Start {

        @Test
        @DisplayName("체험 없는 플랜은 ACTIVE로 시작하고 첫 청구일은 한 달 뒤 주기 종료 시점이다")
        void startsActiveWithoutTrial() {
            Subscription sub = activeSubscription();

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(sub.getCurrentPeriodStart()).isEqualTo(T0);
            assertThat(sub.getCurrentPeriodEnd()).isEqualTo(Instant.parse("2026-12-25T00:00:00Z"));
            assertThat(sub.getNextBillingAt()).isEqualTo(sub.getCurrentPeriodEnd());
            assertThat(sub.getRetryCount()).isZero();
            assertThat(sub.isCancelAtPeriodEnd()).isFalse();
            assertThat(sub.getVersion()).isZero();

            assertThat(sub.isDue(T0)).as("시작 시점에는 청구 대상이 아니다").isFalse();
            assertThat(sub.isDue(Instant.parse("2026-12-25T00:00:00Z"))).as("청구일 도래").isTrue();
        }

        @Test
        @DisplayName("체험 플랜은 TRIAL로 시작하고 체험 종료일이 첫 청구일이 된다")
        void startsTrialWhenPlanHasTrialDays() {
            Subscription sub = trialSubscription();

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
            assertThat(sub.getCurrentPeriodEnd()).isEqualTo(T0.plus(14, DAYS));
            assertThat(sub.getNextBillingAt()).isEqualTo(T0.plus(14, DAYS));
        }

        @Test
        @DisplayName("체험 플랜은 빌링키가 없어도 시작할 수 있다(첫 결제 전 카드 등록 유도)")
        void trialCanStartWithoutBillingKey() {
            Subscription sub = Subscription.start(customer, trialPlan, null, T0);

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
            assertThat(sub.getBillingKey()).isNull();
        }

        @Test
        @DisplayName("체험 없는 플랜에 빌링키가 없으면 시작할 수 없다")
        void rejectsImmediatePlanWithoutBillingKey() {
            assertThatThrownBy(() -> Subscription.start(customer, monthlyPlan, null, T0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("빌링키");
        }

        @Test
        @DisplayName("폐기된 빌링키로는 구독을 시작할 수 없다")
        void rejectsRevokedBillingKey() {
            billingKey.revoke();

            assertThatThrownBy(() -> Subscription.start(customer, monthlyPlan, billingKey, T0))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("빌링키");
        }
    }

    @Nested
    @DisplayName("상태 전이 규칙")
    class Transition {

        @Test
        @DisplayName("정의된 전이만 허용한다(전체 매트릭스)")
        void followsTransitionMatrix() {
            Map<SubscriptionStatus, Set<SubscriptionStatus>> expected = Map.of(
                    SubscriptionStatus.TRIAL, EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE,
                            SubscriptionStatus.CANCELED, SubscriptionStatus.EXPIRED),
                    SubscriptionStatus.ACTIVE, EnumSet.of(SubscriptionStatus.PAST_DUE, SubscriptionStatus.PAUSED,
                            SubscriptionStatus.CANCELED, SubscriptionStatus.EXPIRED),
                    SubscriptionStatus.PAST_DUE, EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELED,
                            SubscriptionStatus.EXPIRED),
                    SubscriptionStatus.PAUSED, EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELED,
                            SubscriptionStatus.EXPIRED),
                    SubscriptionStatus.CANCELED, EnumSet.of(SubscriptionStatus.EXPIRED),
                    SubscriptionStatus.EXPIRED, EnumSet.noneOf(SubscriptionStatus.class)
            );

            for (SubscriptionStatus from : SubscriptionStatus.values()) {
                for (SubscriptionStatus to : SubscriptionStatus.values()) {
                    assertThat(from.canTransitionTo(to))
                            .as("%s -> %s", from, to)
                            .isEqualTo(expected.get(from).contains(to));
                }
            }
        }

        @Test
        @DisplayName("만료된 구독은 어떤 상태로도 전이할 수 없다")
        void expiredIsTerminal() {
            Subscription sub = activeSubscription();
            sub.expire();

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
            assertThat(sub.isTerminal()).isTrue();
            assertThat(sub.getNextBillingAt()).isNull();

            assertThatThrownBy(() -> sub.transitionTo(SubscriptionStatus.ACTIVE))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .hasMessageContaining("EXPIRED");
        }

        @Test
        @DisplayName("만료 처리는 여러 번 호출해도 안전하다(배치 재실행)")
        void expireIsIdempotent() {
            Subscription sub = activeSubscription();

            sub.expire();
            sub.expire();

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        }

        @Test
        @DisplayName("일시정지 상태에서는 청구도 연체 처리도 할 수 없다")
        void pausedIsNotBillable() {
            Subscription sub = activeSubscription();
            sub.pause();

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.PAUSED);
            assertThat(sub.getNextBillingAt()).isNull();
            assertThat(sub.isDue(T0.plus(30, DAYS))).isFalse();

            assertThatThrownBy(() -> sub.markPastDue(T0))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThatThrownBy(() -> sub.registerPaymentFailure(3, T0))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        @DisplayName("일시정지 후 재개하면 재개 시점부터 새 주기를 센다")
        void resumeStartsNewPeriodFromNow() {
            Subscription sub = activeSubscription();
            sub.pause();

            Instant resumedAt = T0.plus(10, DAYS);
            sub.resume(resumedAt);

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(sub.getCurrentPeriodStart()).isEqualTo(resumedAt);
            assertThat(sub.getCurrentPeriodEnd()).isEqualTo(Instant.parse("2027-01-05T00:00:00Z"));
            assertThat(sub.getNextBillingAt()).isEqualTo(sub.getCurrentPeriodEnd());
        }
    }

    @Nested
    @DisplayName("Dunning (연체·재시도)")
    class Dunning {

        @Test
        @DisplayName("결제 실패는 연체로 기록되고 재시도 시각이 다음 청구 시각으로 옮겨진다")
        void failureMovesToPastDue() {
            Subscription sub = activeSubscription();
            Instant nextRetry = T0.plus(1, DAYS);

            boolean expired = sub.registerPaymentFailure(3, nextRetry);

            assertThat(expired).isFalse();
            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
            assertThat(sub.getRetryCount()).isEqualTo(1);
            assertThat(sub.getNextBillingAt()).isEqualTo(nextRetry);
            assertThat(sub.isDue(T0.plus(2, DAYS))).as("재시도 시각이 지나면 다시 청구 대상").isTrue();
        }

        @Test
        @DisplayName("재시도 한도에 도달하면 구독을 만료시키고 청구를 멈춘다")
        void expiresWhenRetriesExhausted() {
            Subscription sub = activeSubscription();
            int maxRetries = 3;

            assertThat(sub.registerPaymentFailure(maxRetries, T0.plus(1, DAYS))).isFalse();
            assertThat(sub.registerPaymentFailure(maxRetries, T0.plus(2, DAYS))).isFalse();

            assertThat(sub.registerPaymentFailure(maxRetries, T0.plus(3, DAYS)))
                    .as("세 번째 실패로 만료").isTrue();

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
            assertThat(sub.getRetryCount()).isEqualTo(3);
            assertThat(sub.getNextBillingAt()).isNull();
            assertThat(sub.isDue(T0.plus(10, DAYS))).isFalse();
        }

        @Test
        @DisplayName("한도가 1이면 첫 실패에서 바로 만료된다")
        void singleRetryLimitExpiresImmediately() {
            Subscription sub = activeSubscription();

            assertThat(sub.registerPaymentFailure(1, T0.plus(1, DAYS))).isTrue();
            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        }

        @Test
        @DisplayName("연체 중 결제가 성공하면 ACTIVE로 돌아오고 재시도 횟수가 초기화된다")
        void resolvesPastDueOnSuccess() {
            Subscription sub = activeSubscription();
            sub.registerPaymentFailure(3, T0.plus(1, DAYS));
            sub.registerPaymentFailure(3, T0.plus(2, DAYS));

            sub.renew(T0.plus(2, DAYS));

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(sub.getRetryCount()).isZero();
            assertThat(sub.getCurrentPeriodStart()).as("기간 중 결제면 이전 주기 종료부터 이어진다")
                    .isEqualTo(Instant.parse("2026-12-25T00:00:00Z"));
            assertThat(sub.getCurrentPeriodEnd()).isEqualTo(Instant.parse("2027-01-25T00:00:00Z"));
            assertThat(sub.getNextBillingAt()).isEqualTo(sub.getCurrentPeriodEnd());
        }

        @Test
        @DisplayName("결제가 주기 종료 이후로 밀리면 결제 시점부터 새 주기를 센다")
        void latePaymentStartsPeriodFromPaidAt() {
            Subscription sub = activeSubscription();
            Instant latePaidAt = Instant.parse("2026-12-30T00:00:00Z");

            sub.renew(latePaidAt);

            assertThat(sub.getCurrentPeriodStart()).isEqualTo(latePaidAt);
            assertThat(sub.getCurrentPeriodEnd()).isEqualTo(Instant.parse("2027-01-30T00:00:00Z"));
        }

        @Test
        @DisplayName("최대 재시도 횟수가 1 미만이면 설정 오류다")
        void rejectsInvalidMaxRetries() {
            Subscription sub = activeSubscription();

            assertThatThrownBy(() -> sub.registerPaymentFailure(0, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("체험 → 유료 전환")
    class TrialConversion {

        @Test
        @DisplayName("체험 종료 후 결제 성공 시 ACTIVE로 전환되고 다음 주기가 열린다")
        void convertsToActiveOnFirstPayment() {
            Subscription sub = trialSubscription();
            Instant trialEnd = sub.getCurrentPeriodEnd();

            sub.renew(trialEnd);

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(sub.getRetryCount()).isZero();
            assertThat(sub.getCurrentPeriodStart()).isEqualTo(trialEnd);
            assertThat(sub.getCurrentPeriodEnd()).isEqualTo(Instant.parse("2027-01-09T00:00:00Z"));
            assertThat(sub.getNextBillingAt()).isEqualTo(sub.getCurrentPeriodEnd());
        }

        @Test
        @DisplayName("체험 구독은 체험 종료일에 청구 대상이 된다")
        void trialBecomesDueAtTrialEnd() {
            Subscription sub = trialSubscription();

            assertThat(sub.isDue(T0.plus(13, DAYS))).isFalse();
            assertThat(sub.isDue(T0.plus(14, DAYS))).isTrue();
        }
    }

    @Nested
    @DisplayName("해지")
    class Cancellation {

        @Test
        @DisplayName("기간 종료 해지를 예약해도 기간 전까지는 이용 가능하고, 청구는 하지 않는다")
        void cancelAtPeriodEndKeepsAccessUntilPeriodEnd() {
            Subscription sub = activeSubscription();
            Instant periodEnd = sub.getCurrentPeriodEnd();

            sub.cancelAtPeriodEnd(T0.plus(3, DAYS));

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(sub.isCancelAtPeriodEnd()).isTrue();
            assertThat(sub.getCanceledAt()).isEqualTo(T0.plus(3, DAYS));
            assertThat(sub.isDue(periodEnd)).as("해지 예약 구독은 갱신 청구하지 않는다").isFalse();

            assertThat(sub.applyPendingCancellation(periodEnd.minusSeconds(1))).isFalse();
            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

            assertThat(sub.applyPendingCancellation(periodEnd)).isTrue();
            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
            assertThat(sub.getNextBillingAt()).isNull();
            assertThat(sub.isCancelAtPeriodEnd()).isFalse();
        }

        @Test
        @DisplayName("해지 예약 구독에 결제 성공이 들어와도 갱신하지 않고 만료로 확정한다")
        void renewOnCancelScheduledExpires() {
            Subscription sub = activeSubscription();
            sub.cancelAtPeriodEnd(T0.plus(1, DAYS));

            sub.renew(sub.getCurrentPeriodEnd());

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        }

        @Test
        @DisplayName("즉시 해지는 CANCELED로 가고 중복 요청은 무시된다")
        void cancelNowIsIdempotent() {
            Subscription sub = activeSubscription();

            sub.cancelNow(T0.plus(1, DAYS));

            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
            assertThat(sub.getNextBillingAt()).isNull();
            assertThat(sub.getCanceledAt()).isEqualTo(T0.plus(1, DAYS));

            sub.cancelNow(T0.plus(2, DAYS));
            assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        }

        @Test
        @DisplayName("만료된 구독은 해지 예약할 수 없다")
        void cannotScheduleCancelWhenExpired() {
            Subscription sub = activeSubscription();
            sub.expire();

            assertThatThrownBy(() -> sub.cancelAtPeriodEnd(T0))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
    }
}

package com.subflow.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subflow.common.error.InvalidStateTransitionException;
import com.subflow.domain.billingkey.BillingKey;
import com.subflow.domain.customer.Customer;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.plan.Plan;
import com.subflow.domain.subscription.Subscription;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final Instant T0 = Instant.parse("2026-11-25T00:00:00Z");

    private Subscription subscription;

    @BeforeEach
    void setUp() {
        Customer customer = Customer.register("dong@example.com", "동동", null);
        Plan plan = Plan.create("BASIC_MONTHLY", "베이직", new BigDecimal("9900"), "KRW",
                BillingCycle.MONTHLY, 0, null);
        BillingKey billingKey = BillingKey.issue(customer, "TOSS", "billing-token-1", "1234");
        subscription = Subscription.start(customer, plan, billingKey, T0);
    }

    @Test
    @DisplayName("결제는 PENDING으로 생성되고 승인 성공 시 거래 ID와 승인 시각이 기록된다")
    void markSucceeded() {
        Payment payment = Payment.request(subscription, "idem-1", new BigDecimal("9900"), "KRW", "TOSS");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.isPending()).isTrue();

        payment.markSucceeded("pg-tx-1", T0);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.isSucceeded()).isTrue();
        assertThat(payment.getPgTxId()).isEqualTo("pg-tx-1");
        assertThat(payment.getPaidAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("성공한 결제를 다시 성공/실패로 바꿀 수 없다")
    void succeededIsFinalForRetry() {
        Payment payment = Payment.request(subscription, "idem-2", new BigDecimal("9900"), "KRW", "TOSS");
        payment.markSucceeded("pg-tx-2", T0);

        assertThatThrownBy(() -> payment.markSucceeded("pg-tx-3", T0.plusSeconds(1)))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> payment.markFailed("LIMIT_EXCEEDED", "한도 초과"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("실패한 결제는 사유가 남고 재승인할 수 없다(새 멱등키로 새 결제를 만든다)")
    void failedRecordsReason() {
        Payment payment = Payment.request(subscription, "idem-3", new BigDecimal("9900"), "KRW", "TOSS");

        payment.markFailed("CARD_DECLINED", "카드사 승인 거절");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("CARD_DECLINED");
        assertThat(payment.getFailureReason()).isEqualTo("카드사 승인 거절");
        assertThatThrownBy(() -> payment.markSucceeded("pg-tx-4", T0))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("승인 성공 후 취소는 허용된다")
    void cancelAfterSuccess() {
        Payment payment = Payment.request(subscription, "idem-5", new BigDecimal("9900"), "KRW", "TOSS");
        payment.markSucceeded("pg-tx-5", T0);

        payment.markCanceled("고객 요청 환불");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("멱등키가 비어 있으면 결제를 만들 수 없다")
    void rejectsBlankIdempotencyKey() {
        assertThatThrownBy(() -> Payment.request(subscription, " ", new BigDecimal("9900"), "KRW", "TOSS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    @DisplayName("금액이 음수면 결제를 만들 수 없다")
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> Payment.request(subscription, "idem-6", new BigDecimal("-1"), "KRW", "TOSS"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

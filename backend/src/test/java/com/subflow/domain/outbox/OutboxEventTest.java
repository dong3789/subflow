package com.subflow.domain.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subflow.common.error.InvalidStateTransitionException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

    private static final Instant T0 = Instant.parse("2026-11-25T00:00:00Z");

    @Test
    @DisplayName("발행 성공 시 PUBLISHED로 확정되고 발행 시각이 남는다")
    void markPublished() {
        OutboxEvent event = OutboxEvent.pending("Payment", "1", "PAYMENT_SUCCEEDED", "{\"amount\":9900}");

        event.markPublished(T0);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(T0);
        assertThat(event.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("발행 실패는 재시도 시각을 잡고, 한도를 넘으면 FAILED로 확정한다")
    void retriesThenFails() {
        OutboxEvent event = OutboxEvent.pending("Subscription", "7", "SUBSCRIPTION_EXPIRED", "{}");

        assertThat(event.markPublishFailed("broker down", T0.plusSeconds(30), 2)).isFalse();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getNextRetryAt()).isEqualTo(T0.plusSeconds(30));

        assertThat(event.markPublishFailed("broker down", T0.plusSeconds(60), 2)).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getNextRetryAt()).isNull();
        assertThat(event.getLastError()).isEqualTo("broker down");
    }

    @Test
    @DisplayName("이미 끝난 이벤트는 다시 발행 처리할 수 없다")
    void terminalIsFinal() {
        OutboxEvent event = OutboxEvent.pending("Payment", "1", "PAYMENT_SUCCEEDED", "{}");
        event.markPublished(T0);

        assertThatThrownBy(() -> event.markPublished(T0.plusSeconds(1)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}

package com.subflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.subflow.domain.outbox.OutboxEvent;
import com.subflow.domain.outbox.OutboxStatus;
import com.subflow.domain.project.Project;
import com.subflow.service.OutboxService;
import com.subflow.support.IntegrationTestSupport;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Outbox 발행기 테스트.
 *
 * <p>발행기는 "적재된 이벤트를 프로젝트 웹훅으로 내보내는" 마지막 구간이다. 여기서 검증하는 것:
 * HMAC 서명 헤더, 실패 시 지수 백오프 재시도, 한도 초과 시 FAILED 격리, 수신 URL 이 없을 때 건너뜀.
 */
class OutboxPublisherTest extends IntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    @TestConfiguration
    static class FakeTransportConfig {

        @Bean
        @Primary
        RecordingTransport recordingTransport() {
            return new RecordingTransport();
        }
    }

    static class RecordingTransport implements WebhookTransport {

        final List<WebhookDelivery> deliveries = new CopyOnWriteArrayList<>();
        final LinkedList<DeliveryResult> scripted = new LinkedList<>();
        boolean failAll;

        @Override
        public DeliveryResult send(WebhookDelivery delivery) {
            deliveries.add(delivery);
            if (!scripted.isEmpty()) {
                return scripted.removeFirst();
            }
            return failAll ? DeliveryResult.failed(500, "downstream 500") : DeliveryResult.ok(200);
        }

        void reset() {
            deliveries.clear();
            scripted.clear();
            failAll = false;
        }
    }

    @Autowired private OutboxPublisher outboxPublisher;
    @Autowired private OutboxService outboxService;
    @Autowired private RecordingTransport transport;

    @BeforeEach
    void resetTransport() {
        transport.reset();
    }

    @Test
    @DisplayName("PENDING 이벤트를 프로젝트 webhook_url 로 보내고 HMAC 서명 헤더를 붙인다")
    void publishesWithSignature() {
        Project project = createProject("https://example.com/hooks/subflow", "whsec_test_secret");
        OutboxEvent event = outboxService.enqueueForProject(project.getId(), "Subscription", "1",
                OutboxEventTypes.SUBSCRIPTION_RENEWED, Map.of("amount", 9900));

        PublishBatchResult result = outboxPublisher.publishPending(NOW);

        assertThat(result.published()).isEqualTo(1);
        assertThat(transport.deliveries).hasSize(1);
        WebhookDelivery delivery = transport.deliveries.get(0);
        assertThat(delivery.url()).isEqualTo("https://example.com/hooks/subflow");
        assertThat(delivery.body()).contains("\"amount\":9900").contains("\"projectId\":"
                + project.getId());

        String signature = delivery.headers().get(WebhookSigner.HEADER);
        assertThat(signature).startsWith("t=").contains("v1=");
        assertThat(WebhookSigner.verify("whsec_test_secret", signature, delivery.body(), NOW,
                java.time.Duration.ofMinutes(5))).isTrue();
        assertThat(delivery.headers().get("X-SubFlow-Event-Id")).isEqualTo("evt_" + event.getId());
        assertThat(delivery.headers().get("X-SubFlow-Event-Type"))
                .isEqualTo(OutboxEventTypes.SUBSCRIPTION_RENEWED);

        OutboxEvent stored = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(stored.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("전송 실패는 지수 백오프로 재시도하고, 한도를 넘으면 FAILED 로 격리한다")
    void retriestWithBackoffThenFails() {
        Project project = createProject("https://example.com/hooks/subflow", "secret");
        OutboxEvent event = outboxService.enqueueForProject(project.getId(), "Payment", "9",
                OutboxEventTypes.PAYMENT_SUCCEEDED, Map.of("amount", 9900));
        transport.failAll = true;

        PublishBatchResult first = outboxPublisher.publishPending(NOW);
        assertThat(first.retried()).isEqualTo(1);
        OutboxEvent afterFirst = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterFirst.getRetryCount()).isEqualTo(1);
        assertThat(afterFirst.getNextRetryAt()).as("기본 백오프 1초 × 2^0").isEqualTo(NOW.plusSeconds(1));

        PublishBatchResult second = outboxPublisher.publishPending(NOW.plusSeconds(1));
        assertThat(second.retried()).isEqualTo(1);
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().getNextRetryAt())
                .isEqualTo(NOW.plusSeconds(1).plusSeconds(2));

        PublishBatchResult third = outboxPublisher.publishPending(NOW.plusSeconds(3));
        assertThat(third.failed()).isEqualTo(1);
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.FAILED);
    }

    @Test
    @DisplayName("수신 URL 이 없는 프로젝트의 이벤트는 발행 완료로 표시해 큐가 쌓이지 않게 한다")
    void skipsEventsWithoutWebhook() {
        Project project = createProject(null, null);
        OutboxEvent event = outboxService.enqueueForProject(project.getId(), "Subscription", "2",
                OutboxEventTypes.SUBSCRIPTION_CREATED, Map.of());

        PublishBatchResult result = outboxPublisher.publishPending(NOW);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(transport.deliveries).isEmpty();
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    @DisplayName("아직 재시도 시각이 오지 않은 이벤트는 발행 대상이 아니다")
    void doesNotPickEventsBeforeRetryTime() {
        Project project = createProject("https://example.com/hooks/subflow", "secret");
        OutboxEvent event = outboxService.enqueueForProject(project.getId(), "Payment", "3",
                OutboxEventTypes.PAYMENT_FAILED, Map.of("amount", 100));
        transport.failAll = true;
        outboxPublisher.publishPending(NOW);
        transport.failAll = false;
        transport.reset();

        PublishBatchResult result = outboxPublisher.publishPending(NOW.plusSeconds(0));

        assertThat(result.processed()).isZero();
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PENDING);
    }
}

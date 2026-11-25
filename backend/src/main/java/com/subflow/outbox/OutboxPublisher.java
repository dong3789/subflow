package com.subflow.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subflow.config.SubFlowProperties;
import com.subflow.domain.outbox.OutboxEvent;
import com.subflow.domain.outbox.OutboxEventRepository;
import com.subflow.domain.project.Project;
import com.subflow.domain.project.ProjectRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 발행기.
 *
 * <p>{@code status = PENDING and next_retry_at <= now} 이벤트를 오래된 순으로 가져가,
 * 이벤트의 {@code projectId} 로 찾은 프로젝트의 {@code webhook_url} 로 HMAC-SHA256 서명과 함께 POST 한다.
 * 실패는 지수 백오프로 재시도하고, 한도를 넘으면 FAILED 로 격리해 수동 개입 대상으로 남긴다.
 *
 * <p>보낼 곳(webhook_url)이 없는 프로젝트의 이벤트는 PUBLISHED 로 표시해 큐가 무한히 쌓이지 않게 한다.
 */
@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ProjectRepository projectRepository;
    private final WebhookTransport webhookTransport;
    private final ObjectMapper objectMapper;
    private final SubFlowProperties properties;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           ProjectRepository projectRepository,
                           WebhookTransport webhookTransport,
                           ObjectMapper objectMapper,
                           SubFlowProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.projectRepository = projectRepository;
        this.webhookTransport = webhookTransport;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public PublishBatchResult publishPending(Instant now) {
        int batchSize = Math.max(1, properties.outbox().batchSize());
        List<OutboxEvent> events = outboxEventRepository.findPublishable(now, PageRequest.of(0, batchSize));
        int published = 0;
        int skipped = 0;
        int retried = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (OutboxEvent event : events) {
            Optional<Project> project = resolveProject(event);
            if (project.isEmpty() || !project.get().hasWebhook()) {
                event.markPublished(now);
                skipped++;
                continue;
            }
            Project target = project.get();
            String body = event.getPayload();
            String secret = target.getWebhookSecret() == null ? "" : target.getWebhookSecret();

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-SubFlow-Event-Id", "evt_" + event.getId());
            headers.put("X-SubFlow-Event-Type", event.getEventType());
            headers.put("X-SubFlow-Timestamp", String.valueOf(now.getEpochSecond()));
            headers.put(WebhookSigner.HEADER, WebhookSigner.sign(secret, now, body));

            WebhookTransport.DeliveryResult result = webhookTransport.send(
                    new WebhookDelivery(target.getWebhookUrl(), body, headers));
            if (result.success()) {
                event.markPublished(now);
                published++;
            } else {
                boolean exhausted = event.markPublishFailed(result.error(),
                        now.plus(backoff(event.getRetryCount())), properties.outbox().maxRetries());
                if (exhausted) {
                    failed++;
                } else {
                    retried++;
                }
                errors.add("event=" + event.getId() + " type=" + event.getEventType()
                        + " status=" + result.statusCode() + " error=" + result.error());
                log.warn("Outbox 발행 실패 event={} type={} retry={} exhausted={}",
                        event.getId(), event.getEventType(), event.getRetryCount(), exhausted);
            }
        }
        return PublishBatchResult.of(now, published, skipped, retried, failed, errors);
    }

    /** 지수 백오프: 기본 간격 × 2^(이미 실패한 횟수). */
    private Duration backoff(int retryCount) {
        Duration base = properties.outbox().retryBackoff();
        long multiplier = 1L << Math.min(retryCount, 10);
        return base.multipliedBy(multiplier);
    }

    private Optional<Project> resolveProject(OutboxEvent event) {
        Long projectId = projectIdOf(event);
        if (projectId != null) {
            return projectRepository.findById(projectId);
        }
        return projectRepository.findFirstByActiveTrueOrderByIdAsc();
    }

    private Long projectIdOf(OutboxEvent event) {
        try {
            JsonNode node = objectMapper.readTree(event.getPayload());
            JsonNode projectId = node.get("projectId");
            if (projectId != null && !projectId.isNull()) {
                return projectId.asLong();
            }
        } catch (Exception e) {
            log.debug("Outbox payload 에서 projectId 를 읽지 못했습니다. event={}", event.getId());
        }
        return null;
    }
}

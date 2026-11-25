package com.subflow.service;

import com.subflow.common.error.BusinessException;
import com.subflow.domain.audit.ActorType;
import com.subflow.domain.payment.Payment;
import com.subflow.domain.payment.PaymentRepository;
import com.subflow.domain.subscription.Subscription;
import com.subflow.domain.webhook.WebhookInbox;
import com.subflow.domain.webhook.WebhookInboxRepository;
import com.subflow.outbox.OutboxEventTypes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PG 웹훅 처리기.
 *
 * <p>멱등성: {@code webhook_inbox(pg_provider, event_id)} 유니크 제약이 1차 방어선이다.
 * 이미 처리(PROCESSED/IGNORED)된 이벤트는 다시 처리하지 않고 200 을 돌려준다.
 * 처리에 실패한 이벤트(FAILED)는 같은 event_id 로 다시 와도 재처리한다(PG 재전송 복구 경로).
 *
 * <p>전액 환불/취소({@code PAYMENT_CANCELED} / {@code PAYMENT_REFUNDED})를 받으면:
 * <ol>
 *   <li>결제 원장을 CANCELED 로 바꾸고</li>
 *   <li>연동된 구독을 즉시 해지하며(사유 기록)</li>
 *   <li>{@code subscription.canceled} Outbox 이벤트와 감사 로그를 남긴다.</li>
 * </ol>
 * 결제가 끝났는데 구독이 계속 결제되는 사고를 웹훅 한 번으로 막는 것이 목적이다.
 */
@Service
public class WebhookProcessingService {

    private static final Logger log = LoggerFactory.getLogger(WebhookProcessingService.class);

    private static final String EVENT_PAYMENT_CANCELED = "PAYMENT_CANCELED";
    private static final String EVENT_PAYMENT_REFUNDED = "PAYMENT_REFUNDED";

    private final WebhookInboxRepository webhookInboxRepository;
    private final PaymentRepository paymentRepository;
    private final SubscriptionLifecycleService subscriptionLifecycleService;
    private final OutboxService outboxService;
    private final AuditService auditService;

    public WebhookProcessingService(WebhookInboxRepository webhookInboxRepository,
                                    PaymentRepository paymentRepository,
                                    SubscriptionLifecycleService subscriptionLifecycleService,
                                    OutboxService outboxService,
                                    AuditService auditService) {
        this.webhookInboxRepository = webhookInboxRepository;
        this.paymentRepository = paymentRepository;
        this.subscriptionLifecycleService = subscriptionLifecycleService;
        this.outboxService = outboxService;
        this.auditService = auditService;
    }

    @Transactional
    public WebhookProcessResult handle(String provider, WebhookPayload payload, Instant now) {
        if (provider == null || provider.isBlank()) {
            throw BusinessException.badRequest("INVALID_PG_PROVIDER", "PG provider 가 필요합니다.");
        }
        Optional<WebhookInbox> existing = webhookInboxRepository
                .findByPgProviderAndEventId(provider, payload.eventId());
        if (existing.isPresent() && existing.get().isAlreadyHandled()) {
            return new WebhookProcessResult(provider, payload.eventId(),
                    WebhookProcessStatus.DUPLICATE_IGNORED,
                    "이미 처리된 이벤트입니다: " + existing.get().getStatus(), null);
        }

        WebhookInbox inbox = existing.orElseGet(() -> {
            try {
                return webhookInboxRepository.save(WebhookInbox.received(
                        provider, payload.eventId(), payload.eventType(), toJson(payload), now));
            } catch (DataIntegrityViolationException race) {
                // 동시에 같은 이벤트가 들어온 경우: 유니크 제약이 막았다.
                return webhookInboxRepository.findByPgProviderAndEventId(provider, payload.eventId())
                        .orElseThrow(() -> race);
            }
        });

        try {
            WebhookProcessResult result = dispatch(provider, inbox, payload, now);
            if (result.status() != WebhookProcessStatus.PROCESSED) {
                inbox.markIgnored(result.message());
            } else {
                inbox.markProcessed(now);
            }
            return result;
        } catch (RuntimeException e) {
            inbox.markFailed(truncate(e.getMessage(), 500), now);
            log.error("웹훅 처리 실패 provider={} eventId={} type={}", provider, payload.eventId(),
                    payload.eventType(), e);
            return new WebhookProcessResult(provider, payload.eventId(), WebhookProcessStatus.FAILED,
                    "처리 실패: " + e.getMessage(), null);
        }
    }

    private WebhookProcessResult dispatch(String provider, WebhookInbox inbox, WebhookPayload payload, Instant now) {
        if (!EVENT_PAYMENT_CANCELED.equals(payload.eventType())
                && !EVENT_PAYMENT_REFUNDED.equals(payload.eventType())) {
            return new WebhookProcessResult(provider, payload.eventId(), WebhookProcessStatus.IGNORED,
                    "관심 없는 이벤트 타입입니다: " + payload.eventType(), null);
        }
        if (payload.pgTxId() == null || payload.pgTxId().isBlank()) {
            return new WebhookProcessResult(provider, payload.eventId(), WebhookProcessStatus.IGNORED,
                    "PG 거래 ID 가 없습니다.", null);
        }
        Optional<Payment> found = paymentRepository.findByPgTxId(payload.pgTxId());
        if (found.isEmpty()) {
            return new WebhookProcessResult(provider, payload.eventId(), WebhookProcessStatus.IGNORED,
                    "해당 PG 거래의 결제 원장이 없습니다: " + payload.pgTxId(), null);
        }
        return handlePaymentCanceled(provider, payload, found.get(), now);
    }

    private WebhookProcessResult handlePaymentCanceled(String provider, WebhookPayload payload,
                                                       Payment payment, Instant now) {
        String reason = payload.reason() == null || payload.reason().isBlank()
                ? payload.eventType()
                : payload.reason();

        if (payment.getStatus().isSuccessful()) {
            payment.markCanceled(reason);
        } else if (payment.getStatus() != com.subflow.domain.payment.PaymentStatus.CANCELED) {
            return new WebhookProcessResult(provider, payload.eventId(), WebhookProcessStatus.IGNORED,
                    "취소할 수 있는 결제 상태가 아닙니다: " + payment.getStatus(), null);
        }

        Subscription subscription = payment.getSubscription();
        Long subscriptionId = subscription.getId();
        Map<String, Object> paymentPayload = new LinkedHashMap<>();
        paymentPayload.put("paymentId", payment.getId());
        paymentPayload.put("paymentPublicId", payment.getPublicId().toString());
        paymentPayload.put("subscriptionId", subscriptionId);
        paymentPayload.put("pgTxId", payment.getPgTxId());
        paymentPayload.put("amount", payment.getAmount());
        paymentPayload.put("reason", reason);
        paymentPayload.put("eventType", payload.eventType());
        paymentPayload.put("pgProvider", provider);
        outboxService.enqueueForProject(projectIdOf(subscription), "Payment",
                String.valueOf(payment.getId()), OutboxEventTypes.PAYMENT_CANCELED, paymentPayload);

        if (!subscription.isTerminal()) {
            subscriptionLifecycleService.cancel(subscriptionId,
                    "PG " + payload.eventType() + ": " + reason, true, ActorType.SYSTEM, null, now);
            auditService.recordSubscription(ActorType.SYSTEM, null, subscription.getPublicId(),
                    "SUBSCRIPTION_AUTO_CANCELED_BY_WEBHOOK",
                    Map.of("pgTxId", payment.getPgTxId(),
                            "eventType", payload.eventType(),
                            "paymentId", payment.getId()));
        }

        return new WebhookProcessResult(provider, payload.eventId(), WebhookProcessStatus.PROCESSED,
                payload.eventType() + " 처리 완료 — 결제 취소 및 구독 해지", subscriptionId);
    }

    private Long projectIdOf(Subscription subscription) {
        if (subscription.getProject() != null) {
            return subscription.getProject().getId();
        }
        if (subscription.getPlan().getProject() != null) {
            return subscription.getPlan().getProject().getId();
        }
        return null;
    }

    private String toJson(WebhookPayload payload) {
        return "{\"eventId\":\"" + escape(payload.eventId())
                + "\",\"eventType\":\"" + escape(payload.eventType())
                + "\",\"pgTxId\":\"" + escape(payload.pgTxId() == null ? "" : payload.pgTxId())
                + "\",\"reason\":\"" + escape(payload.reason() == null ? "" : payload.reason()) + "\"}";
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

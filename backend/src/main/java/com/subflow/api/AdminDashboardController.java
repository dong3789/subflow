package com.subflow.api;

import com.subflow.api.dto.ApiDtos.AdminSummaryResponse;
import com.subflow.api.dto.ApiDtos.AuditLogResponse;
import com.subflow.api.dto.ApiDtos.BillingBatchResponse;
import com.subflow.api.dto.ApiDtos.PublishResponse;
import com.subflow.api.dto.ApiDtos.ReconciliationResponse;
import com.subflow.domain.outbox.OutboxEventRepository;
import com.subflow.domain.outbox.OutboxStatus;
import com.subflow.domain.payment.PaymentRepository;
import com.subflow.domain.payment.PaymentStatus;
import com.subflow.domain.subscription.SubscriptionRepository;
import com.subflow.domain.subscription.SubscriptionStatus;
import com.subflow.outbox.OutboxPublisher;
import com.subflow.service.AuditService;
import com.subflow.service.BillingExecutionService;
import com.subflow.service.ReconciliationService;
import com.subflow.service.SubscriptionLifecycleService;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영(관리자) API — 감사 로그, 배치 수동 실행, 정합성 점검, Outbox 발행.
 *
 * <p>스케줄러를 기다리지 않고 배치를 직접 돌려 결과를 확인할 수 있게 한다.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminDashboardController {

    private final AuditService auditService;
    private final BillingExecutionService billingExecutionService;
    private final SubscriptionLifecycleService subscriptionLifecycleService;
    private final ReconciliationService reconciliationService;
    private final OutboxPublisher outboxPublisher;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;

    public AdminDashboardController(AuditService auditService,
                                    BillingExecutionService billingExecutionService,
                                    SubscriptionLifecycleService subscriptionLifecycleService,
                                    ReconciliationService reconciliationService,
                                    OutboxPublisher outboxPublisher,
                                    SubscriptionRepository subscriptionRepository,
                                    PaymentRepository paymentRepository,
                                    OutboxEventRepository outboxEventRepository) {
        this.auditService = auditService;
        this.billingExecutionService = billingExecutionService;
        this.subscriptionLifecycleService = subscriptionLifecycleService;
        this.reconciliationService = reconciliationService;
        this.outboxPublisher = outboxPublisher;
        this.subscriptionRepository = subscriptionRepository;
        this.paymentRepository = paymentRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    /** 구독·결제·발행 큐 현황. */
    @GetMapping("/summary")
    public AdminSummaryResponse summary() {
        return new AdminSummaryResponse(
                subscriptionRepository.countByStatus(SubscriptionStatus.ACTIVE),
                subscriptionRepository.countByStatus(SubscriptionStatus.TRIAL),
                subscriptionRepository.countByStatus(SubscriptionStatus.PAST_DUE),
                subscriptionRepository.countByStatus(SubscriptionStatus.CANCELED),
                subscriptionRepository.countByStatus(SubscriptionStatus.EXPIRED),
                paymentRepository.countByStatus(PaymentStatus.PENDING),
                outboxEventRepository.countByStatus(OutboxStatus.PENDING),
                outboxEventRepository.countByStatus(OutboxStatus.FAILED));
    }

    @GetMapping("/audit-logs")
    public List<AuditLogResponse> auditLogs(@RequestParam(defaultValue = "50") int limit) {
        return auditService.recent(limit).stream().map(AuditLogResponse::of).toList();
    }

    /** 만기 구독 결제 배치 수동 실행. */
    @PostMapping("/billing/run")
    public BillingBatchResponse runBilling() {
        return BillingBatchResponse.of(billingExecutionService.runDueBilling(Instant.now()));
    }

    /** Dunning 재시도 배치 수동 실행. */
    @PostMapping("/billing/dunning")
    public BillingBatchResponse runDunning() {
        return BillingBatchResponse.of(billingExecutionService.runDunningRetry(Instant.now()));
    }

    /** 기간 종료 해지 확정 수동 실행. */
    @PostMapping("/subscriptions/apply-cancellations")
    public int applyPendingCancellations() {
        return subscriptionLifecycleService.applyPendingCancellations(Instant.now()).size();
    }

    /** 정합성 점검(PG ↔ 원장 대사) 수동 실행. */
    @PostMapping("/reconciliation/run")
    public ReconciliationResponse runReconciliation() {
        return ReconciliationResponse.of(reconciliationService.run(Instant.now()));
    }

    /** Outbox 발행 수동 실행. */
    @PostMapping("/outbox/publish")
    public PublishResponse publishOutbox() {
        return PublishResponse.of(outboxPublisher.publishPending(Instant.now()));
    }
}

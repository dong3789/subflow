package com.subflow.api.dto;

import com.subflow.domain.audit.AuditLog;
import com.subflow.domain.billingkey.BillingKey;
import com.subflow.domain.customer.Customer;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.plan.Plan;
import com.subflow.domain.project.Project;
import com.subflow.domain.subscription.EffectiveMode;
import com.subflow.domain.subscription.Subscription;
import com.subflow.domain.subscription.SubscriptionChangeHistory;
import com.subflow.outbox.PublishBatchResult;
import com.subflow.pg.MockPgScenario;
import com.subflow.service.BillingBatchResult;
import com.subflow.service.BillingOutcome;
import com.subflow.service.CycleChangePreview;
import com.subflow.service.ReconciliationResult;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 2단계 REST API 요청/응답 DTO 모음. 컨트롤러가 늘어도 한 곳에서 모양을 관리한다. */
public final class ApiDtos {

    private ApiDtos() {
    }

    // ------------------------------------------------------------------
    // 플랜 / 고객 / 빌링키 / 프로젝트
    // ------------------------------------------------------------------

    public record CreatePlanRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull @DecimalMin("0") BigDecimal amount,
            String currency,
            @NotNull BillingCycle billingCycle,
            @Min(0) int trialDays,
            String description,
            Long projectId
    ) {
    }

    public record PlanResponse(Long id, String publicId, String code, String name, BigDecimal amount,
                               String currency, String billingCycle, int trialDays, boolean active,
                               String description) {
        public static PlanResponse of(Plan plan) {
            return new PlanResponse(plan.getId(), plan.getPublicId().toString(), plan.getCode(),
                    plan.getName(), plan.getAmount(), plan.getCurrency(),
                    plan.getBillingCycle().name(), plan.getTrialDays(), plan.isActive(), plan.getDescription());
        }
    }

    public record CreateCustomerRequest(
            @NotBlank @Email String email,
            @NotBlank String name,
            String externalKey,
            Long projectId
    ) {
    }

    public record CustomerResponse(Long id, String publicId, String email, String name, String externalKey) {
        public static CustomerResponse of(Customer customer) {
            return new CustomerResponse(customer.getId(), customer.getPublicId().toString(),
                    customer.getEmail(), customer.getName(), customer.getExternalKey());
        }
    }

    public record RegisterBillingKeyRequest(
            @NotBlank String pgProvider,
            @NotBlank String billingKeyToken,
            @Pattern(regexp = "\\d{4}", message = "카드 마지막 4자리는 숫자 4자리여야 합니다.") String cardLast4
    ) {
    }

    public record BillingKeyResponse(Long id, String pgProvider, String cardLast4, String status,
                                     boolean isDefault) {
        public static BillingKeyResponse of(BillingKey billingKey) {
            return new BillingKeyResponse(billingKey.getId(), billingKey.getPgProvider(),
                    billingKey.getCardLast4(), billingKey.getStatus().name(), billingKey.isDefault());
        }
    }

    public record CreateProjectRequest(@NotBlank String name, String webhookUrl, String webhookSecret) {
    }

    /**
     * 프로젝트 응답. {@code apiKey} 는 생성 응답에서만 값이 있고 이후 조회에서는 null 이다.
     */
    public record ProjectResponse(Long id, String publicId, String name, String apiKeyPrefix,
                                  String webhookUrl, boolean active, String apiKey) {
        public static ProjectResponse of(Project project, String apiKey) {
            return new ProjectResponse(project.getId(), project.getPublicId().toString(), project.getName(),
                    project.getApiKeyPrefix(), project.getWebhookUrl(), project.isActive(), apiKey);
        }
    }

    // ------------------------------------------------------------------
    // 구독
    // ------------------------------------------------------------------

    public record CreateSubscriptionRequest(
            @NotNull Long customerId,
            @NotNull Long planId,
            Long billingKeyId,
            @Min(1) Integer quantity
    ) {
    }

    public record SubscriptionResponse(
            Long id,
            String publicId,
            String status,
            Long customerId,
            Long planId,
            String planCode,
            String billingCycle,
            Integer anchorDay,
            int quantity,
            BigDecimal amount,
            String currency,
            int billingCycleCount,
            Instant currentPeriodStart,
            Instant currentPeriodEnd,
            Instant nextBillingAt,
            Instant lastBilledAt,
            int retryCount,
            boolean cancelAtPeriodEnd,
            Instant canceledAt,
            String cancelReason
    ) {
        public static SubscriptionResponse of(Subscription subscription) {
            return new SubscriptionResponse(
                    subscription.getId(), subscription.getPublicId().toString(),
                    subscription.getStatus().name(),
                    subscription.getCustomer().getId(),
                    subscription.getPlan().getId(),
                    subscription.getPlan().getCode(),
                    subscription.effectiveBillingCycle().name(),
                    subscription.getAnchorDay(),
                    subscription.getQuantity(),
                    subscription.nextBillingAmount(),
                    subscription.currency(),
                    subscription.getBillingCycleCount(),
                    subscription.getCurrentPeriodStart(),
                    subscription.getCurrentPeriodEnd(),
                    subscription.getNextBillingAt(),
                    subscription.getLastBilledAt(),
                    subscription.getRetryCount(),
                    subscription.isCancelAtPeriodEnd(),
                    subscription.getCanceledAt(),
                    subscription.getCancelReason());
        }
    }

    public record ChangeCycleRequest(
            @NotNull BillingCycle billingCycle,
            EffectiveMode effectiveMode,
            String reason
    ) {
    }

    public record ChangePlanRequest(
            @NotNull Long planId,
            EffectiveMode effectiveMode,
            String reason
    ) {
    }

    public record ChangeQuantityRequest(
            @Min(1) @Max(9999) int quantity,
            EffectiveMode effectiveMode,
            String reason
    ) {
    }

    public record ChangeBillingDateRequest(
            @Min(1) @Max(31) int anchorDay,
            EffectiveMode effectiveMode,
            String reason
    ) {
    }

    public record CancelRequest(String reason, boolean immediate) {
    }

    public record CyclePreviewResponse(Long subscriptionId, String currentCycle, String newCycle,
                                       Integer currentAnchorDay, Integer newAnchorDay,
                                       Instant currentNextBillingAt, Instant previewNextBillingAt,
                                       boolean immediate) {
        public static CyclePreviewResponse of(CycleChangePreview preview) {
            return new CyclePreviewResponse(preview.subscriptionId(), preview.currentCycle(),
                    preview.newCycle(), preview.currentAnchorDay(), preview.newAnchorDay(),
                    preview.currentNextBillingAt(), preview.previewNextBillingAt(), preview.immediate());
        }
    }

    public record ChangeHistoryResponse(Long id, String changeType, String beforeState, String afterState,
                                        String effectiveMode, String reason, String actorType,
                                        String actorId, Instant createdAt) {
        public static ChangeHistoryResponse of(SubscriptionChangeHistory history) {
            return new ChangeHistoryResponse(history.getId(), history.getChangeType().name(),
                    history.getBeforeState(), history.getAfterState(), history.getEffectiveMode().name(),
                    history.getReason(), history.getActorType().name(), history.getActorId(),
                    history.getCreatedAt());
        }
    }

    // ------------------------------------------------------------------
    // 결제 실행 결과
    // ------------------------------------------------------------------

    public record BillingOutcomeResponse(Long subscriptionId, Long paymentId, String paymentPublicId,
                                         String status, String message, BigDecimal amount, String pgTxId,
                                         int billingCycleCount, Instant nextBillingAt) {
        public static BillingOutcomeResponse of(BillingOutcome outcome) {
            return new BillingOutcomeResponse(outcome.subscriptionId(), outcome.paymentId(),
                    outcome.paymentPublicId(), outcome.status().name(), outcome.message(),
                    outcome.amount(), outcome.pgTxId(), outcome.billingCycleCount(),
                    outcome.nextBillingAt());
        }
    }

    public record BillingBatchResponse(Instant executedAt, int total, long succeeded, long failed,
                                       long skipped, long pendingConfirmation,
                                       List<BillingOutcomeResponse> outcomes) {
        public static BillingBatchResponse of(BillingBatchResult result) {
            return new BillingBatchResponse(result.executedAt(), result.total(), result.succeeded(),
                    result.failed(), result.skipped(), result.pendingConfirmation(),
                    result.outcomes().stream().map(BillingOutcomeResponse::of).toList());
        }
    }

    // ------------------------------------------------------------------
    // 운영/관리
    // ------------------------------------------------------------------

    public record AuditLogResponse(Long id, String actorType, String actorId, String targetType,
                                   String targetId, String action, String metadata, Instant createdAt) {
        public static AuditLogResponse of(AuditLog log) {
            return new AuditLogResponse(log.getId(), log.getActorType().name(), log.getActorId(),
                    log.getTargetType(), log.getTargetId().toString(), log.getAction(),
                    log.getMetadata(), log.getCreatedAt());
        }
    }

    public record ReconciliationResponse(Long logId, String targetDate, Instant executedAt, int checkedCount,
                                         int mismatchCount, int autoFixedCount, int unresolvedCount,
                                         List<String> details) {
        public static ReconciliationResponse of(ReconciliationResult result) {
            return new ReconciliationResponse(result.logId(), result.targetDate().toString(),
                    result.executedAt(), result.checkedCount(), result.mismatchCount(),
                    result.autoFixedCount(), result.unresolvedCount(), result.details());
        }
    }

    public record PublishResponse(Instant executedAt, int published, int skipped, int retried, int failed,
                                  List<String> errors) {
        public static PublishResponse of(PublishBatchResult result) {
            return new PublishResponse(result.executedAt(), result.published(), result.skipped(),
                    result.retried(), result.failed(), result.errors());
        }
    }

    public record AdminSummaryResponse(long activeSubscriptions, long trialSubscriptions, long pastDueSubscriptions,
                                       long canceledSubscriptions, long expiredSubscriptions,
                                       long pendingPayments, long pendingOutboxEvents, long failedOutboxEvents) {
    }

    public record MockScenarioRequest(@NotNull MockPgScenario scenario) {
    }

    public record MockPgStatusResponse(String defaultScenario, long totalTransactions, long approvedTransactions) {
    }

    public record WebhookResponse(String status, String provider, String eventId, String message,
                                  Long subscriptionId) {
    }
}

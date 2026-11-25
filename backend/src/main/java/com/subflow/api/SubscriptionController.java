package com.subflow.api;

import com.subflow.api.dto.ApiDtos.BillingOutcomeResponse;
import com.subflow.api.dto.ApiDtos.CancelRequest;
import com.subflow.api.dto.ApiDtos.ChangeBillingDateRequest;
import com.subflow.api.dto.ApiDtos.ChangeCycleRequest;
import com.subflow.api.dto.ApiDtos.ChangeHistoryResponse;
import com.subflow.api.dto.ApiDtos.ChangePlanRequest;
import com.subflow.api.dto.ApiDtos.ChangeQuantityRequest;
import com.subflow.api.dto.ApiDtos.CreateSubscriptionRequest;
import com.subflow.api.dto.ApiDtos.CyclePreviewResponse;
import com.subflow.api.dto.ApiDtos.SubscriptionResponse;
import com.subflow.domain.audit.ActorType;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.subscription.EffectiveMode;
import com.subflow.domain.subscription.Subscription;
import com.subflow.service.BillingExecutionService;
import com.subflow.service.BillingReason;
import com.subflow.service.SubscriptionLifecycleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구독 API — 생성, 조회, 당겨받기, 주기/플랜/수량/결제일 변경, 해지, 복구, 변경 이력.
 *
 * <p>모든 변경은 같은 구독 안에서 이뤄지고(해지/재생성 없음), 변경 이력과 감사 로그가 함께 남는다.
 * 행위자는 {@code X-Actor-Type}/{@code X-Actor-Id} 헤더로 표기한다(기본 USER).
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionLifecycleService lifecycleService;
    private final BillingExecutionService billingExecutionService;

    public SubscriptionController(SubscriptionLifecycleService lifecycleService,
                                  BillingExecutionService billingExecutionService) {
        this.lifecycleService = lifecycleService;
        this.billingExecutionService = billingExecutionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionResponse create(@Valid @RequestBody CreateSubscriptionRequest request,
                                       HttpServletRequest http) {
        ActorHeaders.Actor actor = ActorHeaders.resolve(http, ActorType.USER);
        Subscription subscription = lifecycleService.create(
                new com.subflow.service.CreateSubscriptionCommand(request.customerId(), request.planId(),
                        request.billingKeyId(), request.quantity()),
                actor.type(), actor.id(), Instant.now());
        return SubscriptionResponse.of(subscription);
    }

    @GetMapping("/{subscriptionId}")
    public SubscriptionResponse get(@PathVariable Long subscriptionId) {
        return SubscriptionResponse.of(lifecycleService.get(subscriptionId));
    }

    @GetMapping
    public List<SubscriptionResponse> findByCustomer(@RequestParam Long customerId) {
        return lifecycleService.findByCustomer(customerId).stream()
                .map(SubscriptionResponse::of).toList();
    }

    /** 당겨받기(즉시 갱신). 당일 중복 결제는 409 DUPLICATE_BILLING_ATTEMPT 로 차단된다. */
    @PostMapping("/{subscriptionId}/renew")
    public BillingOutcomeResponse renewNow(@PathVariable Long subscriptionId) {
        return BillingOutcomeResponse.of(
                billingExecutionService.renewNow(subscriptionId, Instant.now()));
    }

    @GetMapping("/{subscriptionId}/cycle-change/preview")
    public CyclePreviewResponse previewCycleChange(@PathVariable Long subscriptionId,
                                                   @RequestParam BillingCycle billingCycle,
                                                   @RequestParam(required = false)
                                                   EffectiveMode effectiveMode) {
        return CyclePreviewResponse.of(lifecycleService.previewCycleChange(
                subscriptionId, billingCycle, effectiveMode, Instant.now()));
    }

    @PostMapping("/{subscriptionId}/cycle-change")
    public SubscriptionResponse changeCycle(@PathVariable Long subscriptionId,
                                            @Valid @RequestBody ChangeCycleRequest request,
                                            HttpServletRequest http) {
        ActorHeaders.Actor actor = ActorHeaders.resolve(http, ActorType.USER);
        return SubscriptionResponse.of(lifecycleService.changeCycle(subscriptionId,
                request.billingCycle(), request.effectiveMode(), request.reason(),
                actor.type(), actor.id(), Instant.now()));
    }

    @PostMapping("/{subscriptionId}/plan-change")
    public SubscriptionResponse changePlan(@PathVariable Long subscriptionId,
                                           @Valid @RequestBody ChangePlanRequest request,
                                           HttpServletRequest http) {
        ActorHeaders.Actor actor = ActorHeaders.resolve(http, ActorType.USER);
        return SubscriptionResponse.of(lifecycleService.changePlan(subscriptionId, request.planId(),
                request.effectiveMode(), request.reason(), actor.type(), actor.id(), Instant.now()));
    }

    @PostMapping("/{subscriptionId}/quantity-change")
    public SubscriptionResponse changeQuantity(@PathVariable Long subscriptionId,
                                               @Valid @RequestBody ChangeQuantityRequest request,
                                               HttpServletRequest http) {
        ActorHeaders.Actor actor = ActorHeaders.resolve(http, ActorType.USER);
        return SubscriptionResponse.of(lifecycleService.changeQuantity(subscriptionId,
                request.quantity(), request.effectiveMode(), request.reason(), actor.type(), actor.id()));
    }

    @PostMapping("/{subscriptionId}/billing-date-change")
    public SubscriptionResponse changeBillingDate(@PathVariable Long subscriptionId,
                                                  @Valid @RequestBody ChangeBillingDateRequest request,
                                                  HttpServletRequest http) {
        ActorHeaders.Actor actor = ActorHeaders.resolve(http, ActorType.USER);
        return SubscriptionResponse.of(lifecycleService.changeBillingDate(subscriptionId,
                request.anchorDay(), request.effectiveMode(), request.reason(),
                actor.type(), actor.id(), Instant.now()));
    }

    @PostMapping("/{subscriptionId}/cancel")
    public SubscriptionResponse cancel(@PathVariable Long subscriptionId,
                                       @RequestBody(required = false) CancelRequest request,
                                       HttpServletRequest http) {
        ActorHeaders.Actor actor = ActorHeaders.resolve(http, ActorType.USER);
        CancelRequest effective = request == null ? new CancelRequest(null, true) : request;
        return SubscriptionResponse.of(lifecycleService.cancel(subscriptionId, effective.reason(),
                effective.immediate(), actor.type(), actor.id(), Instant.now()));
    }

    /** 관리자 복구. 해지된 구독만 복구할 수 있다. */
    @PostMapping("/{subscriptionId}/restore")
    public SubscriptionResponse restore(@PathVariable Long subscriptionId, HttpServletRequest http) {
        ActorHeaders.Actor actor = ActorHeaders.resolve(http, ActorType.ADMIN);
        return SubscriptionResponse.of(
                lifecycleService.restore(subscriptionId, actor.type(), actor.id(), Instant.now()));
    }

    @GetMapping("/{subscriptionId}/history")
    public List<ChangeHistoryResponse> history(@PathVariable Long subscriptionId) {
        return lifecycleService.histories(subscriptionId).stream()
                .map(ChangeHistoryResponse::of).toList();
    }

    /** 배치를 기다리지 않고 특정 구독을 직접 청구(운영 도구). 중복 가드는 그대로 적용된다. */
    @PostMapping("/{subscriptionId}/charge")
    public BillingOutcomeResponse charge(@PathVariable Long subscriptionId) {
        return BillingOutcomeResponse.of(billingExecutionService.charge(
                subscriptionId, BillingReason.SCHEDULED, Instant.now()));
    }
}

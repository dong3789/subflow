package com.subflow.api;

import com.subflow.api.dto.ApiDtos.CreatePlanRequest;
import com.subflow.api.dto.ApiDtos.PlanResponse;
import com.subflow.service.PlanService;
import jakarta.validation.Valid;
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

/** 플랜(상품) API. */
@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping
    public List<PlanResponse> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return planService.findAll(includeInactive).stream().map(PlanResponse::of).toList();
    }

    @GetMapping("/{planId}")
    public PlanResponse get(@PathVariable Long planId) {
        return PlanResponse.of(planService.get(planId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanResponse create(@Valid @RequestBody CreatePlanRequest request) {
        return PlanResponse.of(planService.create(request.code(), request.name(), request.amount(),
                request.currency(), request.billingCycle(), request.trialDays(), request.description(),
                request.projectId()));
    }

    /** 단종. 신규 구독·플랜 변경은 막히고, 기존 구독의 정기결제는 유지된다. */
    @PostMapping("/{planId}/deactivate")
    public PlanResponse deactivate(@PathVariable Long planId) {
        return PlanResponse.of(planService.deactivate(planId));
    }

    @PostMapping("/{planId}/activate")
    public PlanResponse activate(@PathVariable Long planId) {
        return PlanResponse.of(planService.activate(planId));
    }
}

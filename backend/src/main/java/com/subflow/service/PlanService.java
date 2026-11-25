package com.subflow.service;

import com.subflow.common.error.BusinessException;
import com.subflow.common.error.EntityNotFoundException;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.plan.Plan;
import com.subflow.domain.plan.PlanRepository;
import com.subflow.domain.project.Project;
import com.subflow.domain.project.ProjectRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 플랜(상품) 관리 — 생성·조회·단종. */
@Service
public class PlanService {

    private final PlanRepository planRepository;
    private final ProjectRepository projectRepository;

    public PlanService(PlanRepository planRepository, ProjectRepository projectRepository) {
        this.planRepository = planRepository;
        this.projectRepository = projectRepository;
    }

    @Transactional
    public Plan create(String code, String name, BigDecimal amount, String currency,
                       BillingCycle billingCycle, int trialDays, String description, Long projectId) {
        if (planRepository.existsByCode(code)) {
            throw BusinessException.conflict("PLAN_CODE_DUPLICATED", "이미 같은 코드의 플랜이 있습니다: " + code);
        }
        Project project = projectId == null ? null : projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("프로젝트", projectId));
        return planRepository.save(Plan.create(code, name, amount, currency, billingCycle,
                trialDays, description, project));
    }

    @Transactional(readOnly = true)
    public List<Plan> findAll(boolean includeInactive) {
        return includeInactive ? planRepository.findAll() : planRepository.findAllByActiveTrueOrderByAmountAsc();
    }

    @Transactional(readOnly = true)
    public Plan get(Long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new EntityNotFoundException("플랜", planId));
    }

    /** 플랜 단종. 신규 구독·플랜 변경은 막히고, 기존 구독의 정기결제는 유지된다. */
    @Transactional
    public Plan deactivate(Long planId) {
        Plan plan = get(planId);
        plan.deactivate();
        return plan;
    }

    @Transactional
    public Plan activate(Long planId) {
        Plan plan = get(planId);
        plan.activate();
        return plan;
    }
}

package com.subflow.domain.plan;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    Optional<Plan> findByCode(String code);

    boolean existsByCode(String code);

    List<Plan> findAllByActiveTrueOrderByAmountAsc();
}

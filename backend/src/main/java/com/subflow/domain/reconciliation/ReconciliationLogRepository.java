package com.subflow.domain.reconciliation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationLogRepository extends JpaRepository<ReconciliationLog, Long> {

    List<ReconciliationLog> findByTargetDateOrderByExecutedAtDesc(LocalDate targetDate);

    Optional<ReconciliationLog> findTopByOrderByExecutedAtDesc();

    List<ReconciliationLog> findByMismatchCountGreaterThanOrderByExecutedAtDesc(int threshold);
}

package com.subflow.domain.subscription;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionChangeHistoryRepository
        extends JpaRepository<SubscriptionChangeHistory, Long> {

    List<SubscriptionChangeHistory> findBySubscriptionIdOrderByCreatedAtDescIdDesc(Long subscriptionId);

    List<SubscriptionChangeHistory> findBySubscriptionIdAndChangeTypeOrderByCreatedAtDesc(
            Long subscriptionId, ChangeType changeType);

    long countBySubscriptionId(Long subscriptionId);
}

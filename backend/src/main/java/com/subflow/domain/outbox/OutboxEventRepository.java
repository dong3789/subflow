package com.subflow.domain.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** 발행기가 가져갈 다음 배치. 재시도 시각이 지났거나 아직 없는 PENDING 이벤트. */
    @Query("""
            select e from OutboxEvent e
            where e.status = com.subflow.domain.outbox.OutboxStatus.PENDING
              and (e.nextRetryAt is null or e.nextRetryAt <= :now)
            order by e.id asc
            """)
    List<OutboxEvent> findPublishable(@Param("now") Instant now, Pageable pageable);

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByIdAsc(String aggregateType, String aggregateId);

    long countByStatus(OutboxStatus status);
}

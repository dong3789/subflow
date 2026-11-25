package com.subflow.domain.subscription;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByCustomerId(Long customerId);

    List<Subscription> findByCustomerIdAndStatus(Long customerId, SubscriptionStatus status);

    /**
     * 낙관적 락 충돌을 피하기 위해 행을 잠그고 읽는다.
     * 2단계 결제 실행 시 분산 락(Redisson)과 함께 이중 방어로 쓴다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.id = :id")
    Optional<Subscription> findByIdForUpdate(@Param("id") Long id);

    /** 청구 대상 상태이면서 청구 시각이 지난 구독 (배치 조회). */
    @Query("""
            select s from Subscription s
            where s.status in (com.subflow.domain.subscription.SubscriptionStatus.TRIAL,
                               com.subflow.domain.subscription.SubscriptionStatus.ACTIVE,
                               com.subflow.domain.subscription.SubscriptionStatus.PAST_DUE)
              and s.cancelAtPeriodEnd = false
              and s.nextBillingAt is not null
              and s.nextBillingAt <= :now
            order by s.nextBillingAt asc
            """)
    List<Subscription> findDueSubscriptions(@Param("now") Instant now, Pageable pageable);

    /** 해지 예약 후 기간이 끝난 구독 (해지 확정 배치). */
    @Query("""
            select s from Subscription s
            where s.cancelAtPeriodEnd = true
              and s.currentPeriodEnd <= :now
              and s.status in (com.subflow.domain.subscription.SubscriptionStatus.TRIAL,
                               com.subflow.domain.subscription.SubscriptionStatus.ACTIVE,
                               com.subflow.domain.subscription.SubscriptionStatus.PAST_DUE)
            """)
    List<Subscription> findExpiredByCancellation(@Param("now") Instant now);

    long countByStatus(SubscriptionStatus status);
}

package com.subflow.domain.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<Payment> findBySubscriptionIdOrderByIdDesc(Long subscriptionId);

    List<Payment> findBySubscriptionIdAndStatus(Long subscriptionId, PaymentStatus status);

    /** 정합성 점검: 특정 시각 구간에 성공한 결제 합계. */
    @Query("""
            select coalesce(sum(p.amount), 0) from Payment p
            where p.status = com.subflow.domain.payment.PaymentStatus.SUCCEEDED
              and p.paidAt >= :from and p.paidAt < :to
            """)
    BigDecimal sumSucceededAmountBetween(@Param("from") Instant from, @Param("to") Instant to);

    long countByStatus(PaymentStatus status);
}

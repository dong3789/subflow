package com.subflow.domain.payment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    List<PaymentAttempt> findByPaymentIdOrderByAttemptNoAsc(Long paymentId);

    Optional<PaymentAttempt> findTopByPaymentIdOrderByAttemptNoDesc(Long paymentId);

    long countByPaymentIdAndStatus(Long paymentId, PaymentAttemptStatus status);
}

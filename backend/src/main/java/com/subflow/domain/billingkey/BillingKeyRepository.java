package com.subflow.domain.billingkey;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillingKeyRepository extends JpaRepository<BillingKey, Long> {

    List<BillingKey> findByCustomerIdOrderByIdDesc(Long customerId);

    List<BillingKey> findByCustomerIdAndStatusOrderByIdDesc(Long customerId, BillingKeyStatus status);

    @Query("select b from BillingKey b where b.customer.id = :customerId and b.isDefault = true")
    Optional<BillingKey> findDefaultByCustomerId(@Param("customerId") Long customerId);

    @Query("select b from BillingKey b where b.customer.id = :customerId and b.status = :status order by b.id desc")
    List<BillingKey> findUsableByCustomerId(@Param("customerId") Long customerId,
                                            @Param("status") BillingKeyStatus status);
}

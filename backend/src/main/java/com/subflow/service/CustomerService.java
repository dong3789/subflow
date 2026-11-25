package com.subflow.service;

import com.subflow.common.error.BusinessException;
import com.subflow.common.error.EntityNotFoundException;
import com.subflow.domain.billingkey.BillingKey;
import com.subflow.domain.billingkey.BillingKeyRepository;
import com.subflow.domain.customer.Customer;
import com.subflow.domain.customer.CustomerRepository;
import com.subflow.domain.project.Project;
import com.subflow.domain.project.ProjectRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 고객·빌링키 관리. 카드 원본은 받지 않고 PG 가 준 빌링키 토큰만 저장한다. */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final BillingKeyRepository billingKeyRepository;
    private final ProjectRepository projectRepository;

    public CustomerService(CustomerRepository customerRepository,
                           BillingKeyRepository billingKeyRepository,
                           ProjectRepository projectRepository) {
        this.customerRepository = customerRepository;
        this.billingKeyRepository = billingKeyRepository;
        this.projectRepository = projectRepository;
    }

    @Transactional
    public Customer register(String email, String name, String externalKey, Long projectId) {
        if (customerRepository.existsByEmail(email == null ? "" : email.trim().toLowerCase())) {
            throw BusinessException.conflict("CUSTOMER_EMAIL_DUPLICATED", "이미 가입된 이메일입니다: " + email);
        }
        Project project = projectId == null ? null : projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("프로젝트", projectId));
        return customerRepository.save(Customer.register(email, name, externalKey, project));
    }

    @Transactional(readOnly = true)
    public Customer get(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new EntityNotFoundException("고객", customerId));
    }

    @Transactional(readOnly = true)
    public List<BillingKey> billingKeys(Long customerId) {
        return billingKeyRepository.findByCustomerIdOrderByIdDesc(customerId);
    }

    /** 빌링키를 등록하고 기본 키로 지정한다(고객당 기본 키 1개). */
    @Transactional
    public BillingKey registerBillingKey(Long customerId, String pgProvider, String token, String cardLast4) {
        Customer customer = get(customerId);
        billingKeyRepository.findDefaultByCustomerId(customerId).ifPresent(BillingKey::unmarkDefault);
        BillingKey billingKey = billingKeyRepository.save(
                BillingKey.issue(customer, pgProvider, token, cardLast4));
        billingKey.markDefault();
        return billingKey;
    }
}

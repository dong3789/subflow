package com.subflow.support;

import com.subflow.domain.audit.AuditLogRepository;
import com.subflow.domain.billingkey.BillingKey;
import com.subflow.domain.billingkey.BillingKeyRepository;
import com.subflow.domain.customer.Customer;
import com.subflow.domain.customer.CustomerRepository;
import com.subflow.domain.outbox.OutboxEventRepository;
import com.subflow.domain.payment.PaymentAttemptRepository;
import com.subflow.domain.payment.PaymentRepository;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.plan.Plan;
import com.subflow.domain.plan.PlanRepository;
import com.subflow.domain.project.Project;
import com.subflow.domain.project.ProjectRepository;
import com.subflow.domain.reconciliation.ReconciliationLogRepository;
import com.subflow.domain.subscription.Subscription;
import com.subflow.domain.subscription.SubscriptionChangeHistoryRepository;
import com.subflow.domain.subscription.SubscriptionRepository;
import com.subflow.domain.webhook.WebhookInboxRepository;
import com.subflow.pg.MockPgClient;
import com.subflow.service.BillingExecutionService;
import com.subflow.service.ReconciliationService;
import com.subflow.service.SubscriptionLifecycleService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 2단계 통합 테스트 공통 기반.
 *
 * <p>실제 Spring 컨텍스트(H2 · 인메모리 락 · Mock PG)를 띄우고, 테스트 간 데이터를 비운다.
 * 트랜잭션 롤백을 쓰지 않는 이유는 락·커밋 순서·동시성 같은 것들이 실제 커밋 경계에서만 검증되기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    /** 기준 시각: 2026-11-25T00:00:00Z (KST 09:00) */
    protected static final Instant T0 = Instant.parse("2026-11-25T00:00:00Z");
    protected static final Instant DUE = Instant.parse("2026-12-25T00:00:00Z");

    private static final AtomicInteger CODE_SEQUENCE = new AtomicInteger(1);

    @Autowired protected SubscriptionRepository subscriptionRepository;
    @Autowired protected SubscriptionChangeHistoryRepository changeHistoryRepository;
    @Autowired protected PlanRepository planRepository;
    @Autowired protected CustomerRepository customerRepository;
    @Autowired protected BillingKeyRepository billingKeyRepository;
    @Autowired protected PaymentRepository paymentRepository;
    @Autowired protected PaymentAttemptRepository paymentAttemptRepository;
    @Autowired protected OutboxEventRepository outboxEventRepository;
    @Autowired protected WebhookInboxRepository webhookInboxRepository;
    @Autowired protected AuditLogRepository auditLogRepository;
    @Autowired protected ReconciliationLogRepository reconciliationLogRepository;
    @Autowired protected ProjectRepository projectRepository;
    @Autowired protected SubscriptionLifecycleService lifecycleService;
    @Autowired protected BillingExecutionService billingService;
    @Autowired protected ReconciliationService reconciliationService;
    @Autowired protected MockPgClient mockPgClient;
    @Autowired protected EntityManager entityManager;

    @BeforeEach
    void resetDatabase() {
        paymentAttemptRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        changeHistoryRepository.deleteAllInBatch();
        auditLogRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        webhookInboxRepository.deleteAllInBatch();
        reconciliationLogRepository.deleteAllInBatch();
        subscriptionRepository.deleteAllInBatch();
        billingKeyRepository.deleteAllInBatch();
        planRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
        projectRepository.deleteAllInBatch();
        mockPgClient.reset();
    }

    protected Plan createPlan(String amount, BillingCycle cycle, int trialDays) {
        return planRepository.save(Plan.create(
                "PLAN_" + CODE_SEQUENCE.getAndIncrement(), "테스트 플랜", new BigDecimal(amount),
                "KRW", cycle, trialDays, "2단계 테스트용"));
    }

    protected Plan createPlan(String code, String amount, BillingCycle cycle) {
        return planRepository.save(Plan.create(code, "플랜 " + code, new BigDecimal(amount),
                "KRW", cycle, 0, null));
    }

    protected Customer createCustomer() {
        return customerRepository.save(Customer.register(
                "user" + CODE_SEQUENCE.getAndIncrement() + "@example.com", "테스트 고객", null));
    }

    protected BillingKey createBillingKey(Customer customer) {
        return billingKeyRepository.save(
                BillingKey.issue(customer, "MOCK", "billing-token-" + CODE_SEQUENCE.getAndIncrement(), "1234"));
    }

    protected Subscription createSubscription(Plan plan, Customer customer, BillingKey billingKey, Instant startAt) {
        return subscriptionRepository.save(Subscription.start(customer, plan, billingKey, startAt));
    }

    /** 플랜/고객/빌링키를 만들고 ACTIVE 구독을 시작한다. */
    protected Subscription createActiveSubscription(String amount, BillingCycle cycle) {
        Plan plan = createPlan(amount, cycle, 0);
        Customer customer = createCustomer();
        BillingKey billingKey = createBillingKey(customer);
        return createSubscription(plan, customer, billingKey, T0);
    }

    protected Subscription reload(Long subscriptionId) {
        // 테스트는 트랜잭션 밖에서 돌므로 서비스 호출이 이미 커밋되어 있다. 새 조회가 곧 최신 상태다.
        return subscriptionRepository.findById(subscriptionId).orElseThrow();
    }

    protected Project createProject(String webhookUrl, String webhookSecret) {
        int seq = CODE_SEQUENCE.getAndIncrement();
        return projectRepository.save(Project.create("프로젝트 " + seq, "pk_test_" + seq, "hash-" + seq,
                webhookUrl, webhookSecret));
    }
}

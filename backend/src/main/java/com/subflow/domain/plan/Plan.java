package com.subflow.domain.plan;

import com.subflow.domain.project.Project;
import com.subflow.domain.support.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구독 상품(플랜).
 *
 * <p>가격·주기·체험 기간은 플랜이 정하고, 실제 청구는 구독이 이 값을 복사해 진행한다.
 * 플랜 가격을 바꿔도 이미 결제된 기간의 금액은 바뀌지 않는다(결제 원장이 금액을 보관).
 *
 * <p>단종({@code active = false})은 <b>신규 구독·플랜 변경만</b> 막는다. 이미 그 플랜으로 돌고 있는
 * 구독의 정기결제는 계속된다(고객이 산 상품을 일방적으로 끊지 않는다).
 */
@Entity
@Table(name = "plans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Plan extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부(API)에 노출하는 식별자. */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 이 플랜을 소유한 프로젝트(테넌트). null 이면 기본 프로젝트. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    /** 외부/운영에서 쓰는 상품 코드 (예: BASIC_MONTHLY). */
    @Column(name = "code", nullable = false, length = 50, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** ISO 4217 통화 코드. 현재는 KRW 단일 통화를 전제로 한다. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 20)
    private BillingCycle billingCycle;

    /** 체험 일수. 0이면 체험 없이 구독 시작과 동시에 결제한다. */
    @Column(name = "trial_days", nullable = false)
    private int trialDays;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "description", length = 500)
    private String description;

    private Plan(String code, String name, BigDecimal amount, String currency,
                 BillingCycle billingCycle, int trialDays, boolean active, String description) {
        validateAmount(amount);
        if (trialDays < 0) {
            throw new IllegalArgumentException("체험 기간은 0일 이상이어야 합니다: " + trialDays);
        }
        this.publicId = UUID.randomUUID();
        this.code = Objects.requireNonNull(code, "code");
        this.name = Objects.requireNonNull(name, "name");
        this.amount = amount;
        this.currency = currency == null ? "KRW" : currency.toUpperCase();
        this.billingCycle = Objects.requireNonNull(billingCycle, "billingCycle");
        this.trialDays = trialDays;
        this.active = active;
        this.description = description;
    }

    public static Plan create(String code, String name, BigDecimal amount, String currency,
                              BillingCycle billingCycle, int trialDays, String description) {
        return new Plan(code, name, amount, currency, billingCycle, trialDays, true, description);
    }

    public static Plan create(String code, String name, BigDecimal amount, String currency,
                              BillingCycle billingCycle, int trialDays, String description, Project project) {
        Plan plan = new Plan(code, name, amount, currency, billingCycle, trialDays, true, description);
        plan.project = project;
        return plan;
    }

    public boolean hasTrial() {
        return trialDays > 0;
    }

    /** 판매 중인 플랜인지. 단종 플랜은 신규 구독·플랜 변경에 쓸 수 없다. */
    public boolean isActive() {
        return active;
    }

    /**
     * 구독 첫 주기의 종료 시각. 체험이 있으면 체험 일수만큼, 없으면 과금 주기만큼 뒤다.
     */
    public Instant initialPeriodEnd(Instant startedAt) {
        return hasTrial()
                ? startedAt.plus(trialDays, ChronoUnit.DAYS)
                : billingCycle.advance(startedAt);
    }

    /** 수량(좌석 수 등)을 반영한 1회 청구 금액. */
    public BigDecimal amountFor(int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다: " + quantity);
        }
        return amount.multiply(BigDecimal.valueOf(quantity));
    }

    public void activate() {
        this.active = true;
    }

    /**
     * 단종 처리. 신규 구독·플랜 변경은 막히고, 기존 구독의 정기결제는 유지된다.
     */
    public void deactivate() {
        this.active = false;
    }

    public void changePrice(BigDecimal newAmount) {
        validateAmount(newAmount);
        this.amount = newAmount;
    }

    public void changeCycle(BillingCycle billingCycle) {
        this.billingCycle = Objects.requireNonNull(billingCycle, "billingCycle");
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("플랜 금액은 0원 이상이어야 합니다: " + amount);
        }
    }
}

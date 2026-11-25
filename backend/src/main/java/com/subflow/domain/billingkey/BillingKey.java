package com.subflow.domain.billingkey;

import com.subflow.domain.customer.Customer;
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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 카드 빌링키.
 *
 * <p>카드 원본 정보는 저장하지 않는다. PG가 발급한 빌링키 토큰과 표시용 마지막 4자리만 보관하고,
 * 실제 결제는 PG에 토큰을 보내 승인을 요청한다(PCI-DSS 범위 최소화).
 *
 * <p>고객당 기본 빌링키는 1개이며, DB 부분 유니크 인덱스({@code uk_billing_keys_default})가 이를 강제한다.
 */
@Entity
@Table(name = "billing_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingKey extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** PG 식별자 (예: TOSS, PORTONE_V2). */
    @Column(name = "pg_provider", nullable = false, length = 30)
    private String pgProvider;

    /** PG가 발급한 빌링키 토큰. 외부에 노출하지 않는다. */
    @Column(name = "billing_key_token", nullable = false, length = 255)
    private String billingKeyToken;

    /** 카드 표시용 마지막 4자리. */
    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BillingKeyStatus status;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    private BillingKey(Customer customer, String pgProvider, String billingKeyToken, String cardLast4) {
        this.customer = Objects.requireNonNull(customer, "customer");
        this.pgProvider = Objects.requireNonNull(pgProvider, "pgProvider");
        this.billingKeyToken = Objects.requireNonNull(billingKeyToken, "billingKeyToken");
        validateLast4(cardLast4);
        this.cardLast4 = cardLast4;
        this.status = BillingKeyStatus.ACTIVE;
        this.isDefault = false;
    }

    /** PG 인증 결과로 받은 빌링키를 등록한다. 기본키 지정은 별도로 한다. */
    public static BillingKey issue(Customer customer, String pgProvider, String billingKeyToken, String cardLast4) {
        return new BillingKey(customer, pgProvider, billingKeyToken, cardLast4);
    }

    public void markDefault() {
        ensureUsable();
        this.isDefault = true;
    }

    public void unmarkDefault() {
        this.isDefault = false;
    }

    public void deactivate() {
        this.status = BillingKeyStatus.INACTIVE;
        this.isDefault = false;
    }

    public void revoke() {
        this.status = BillingKeyStatus.REVOKED;
        this.isDefault = false;
    }

    public void ensureUsable() {
        if (!status.isUsable()) {
            throw new IllegalStateException("사용할 수 없는 빌링키입니다: status=" + status);
        }
    }

    private void validateLast4(String cardLast4) {
        if (cardLast4 != null && !cardLast4.matches("\\d{4}")) {
            throw new IllegalArgumentException("카드 마지막 4자리는 숫자 4자리여야 합니다: " + cardLast4);
        }
    }
}

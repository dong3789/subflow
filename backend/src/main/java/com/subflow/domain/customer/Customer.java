package com.subflow.domain.customer;

import com.subflow.domain.support.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구독 고객(테넌트).
 *
 * <p>가입 서비스의 사용자와 1:1로 매핑되며, {@code externalKey}로 외부 시스템의 사용자 식별자를 보관한다.
 * 이메일은 중복 가입 방지를 위해 유니크 제약을 갖는다.
 */
@Entity
@Table(name = "customers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 255, unique = true)
    private String email;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 외부 서비스의 사용자 키. 없으면 null(유니크 제약은 NULL을 허용). */
    @Column(name = "external_key", length = 100, unique = true)
    private String externalKey;

    private Customer(String email, String name, String externalKey) {
        this.email = Objects.requireNonNull(email, "email").trim().toLowerCase();
        this.name = Objects.requireNonNull(name, "name");
        this.externalKey = externalKey;
    }

    public static Customer register(String email, String name, String externalKey) {
        return new Customer(email, name, externalKey);
    }

    public void rename(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public void linkExternalKey(String externalKey) {
        this.externalKey = externalKey;
    }
}

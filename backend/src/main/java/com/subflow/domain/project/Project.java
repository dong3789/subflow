package com.subflow.domain.project;

import com.subflow.domain.support.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 프로젝트(테넌트) — 오픈소스 SaaS 로 붙여 쓰는 최상위 격리 단위.
 *
 * <p>각 프로젝트는 자기 API Key 로 인증하고, 자기 {@code webhookUrl} 로 구독 이벤트를 받는다.
 * API Key 원문은 저장하지 않고 SHA-256 해시만 보관하며, 화면·로그에는 {@code apiKeyPrefix} 만 노출한다.
 */
@Entity
@Table(name = "projects")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부(API·웹훅)에 노출하는 식별자. */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** API Key 접두사 (예: pk_live_ab12). 원문 없이도 어느 키인지 식별한다. */
    @Column(name = "api_key_prefix", nullable = false, length = 30, unique = true)
    private String apiKeyPrefix;

    /** API Key SHA-256 해시(hex). */
    @Column(name = "api_key_hash", nullable = false, length = 128, unique = true)
    private String apiKeyHash;

    @Column(name = "webhook_url", length = 500)
    private String webhookUrl;

    /** 웹훅 HMAC-SHA256 서명 키. 외부에 노출하지 않는다. */
    @Column(name = "webhook_secret", length = 255)
    private String webhookSecret;

    @Column(name = "active", nullable = false)
    private boolean active;

    private Project(String name, String apiKeyPrefix, String apiKeyHash,
                    String webhookUrl, String webhookSecret) {
        this.publicId = UUID.randomUUID();
        this.name = Objects.requireNonNull(name, "name");
        this.apiKeyPrefix = Objects.requireNonNull(apiKeyPrefix, "apiKeyPrefix");
        this.apiKeyHash = Objects.requireNonNull(apiKeyHash, "apiKeyHash");
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
        this.active = true;
    }

    public static Project create(String name, String apiKeyPrefix, String apiKeyHash,
                                 String webhookUrl, String webhookSecret) {
        return new Project(name, apiKeyPrefix, apiKeyHash, webhookUrl, webhookSecret);
    }

    public void updateWebhook(String webhookUrl, String webhookSecret) {
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
    }

    public void rename(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    /** 웹훅을 실제로 보낼 곳이 있는지. 없으면 발행기는 이벤트를 건너뛴다. */
    public boolean hasWebhook() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}

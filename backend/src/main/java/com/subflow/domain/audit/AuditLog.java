package com.subflow.domain.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 감사 로그 — 누가(actor) 무엇을(target) 어떻게(action) 바꿨는지 남긴다.
 *
 * <p>{@code targetId} 는 대상 애그리거트의 {@code publicId}(UUID)다. 내부 PK(BIGINT)를 그대로 넣지 않아
 * 식별자 타입 혼용을 막고, 외부에 그대로 내보내도 안전하다.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ActorType actorType;

    @Column(name = "actor_id", length = 64)
    private String actorId;

    @Column(name = "target_type", nullable = false, length = 40)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "action", nullable = false, length = 80)
    private String action;

    /** 부가 정보(변경 전후 값, 요청 출처 등) JSON. 민감정보는 담지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false)
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private AuditLog(ActorType actorType, String actorId, String targetType, UUID targetId,
                     String action, String metadata) {
        this.actorType = Objects.requireNonNull(actorType, "actorType");
        this.actorId = actorId;
        this.targetType = Objects.requireNonNull(targetType, "targetType");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.action = Objects.requireNonNull(action, "action");
        this.metadata = metadata == null || metadata.isBlank() ? "{}" : metadata;
    }

    public static AuditLog record(ActorType actorType, String actorId, String targetType,
                                  UUID targetId, String action, String metadata) {
        return new AuditLog(actorType, actorId, targetType, targetId, action, metadata);
    }
}

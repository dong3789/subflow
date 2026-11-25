package com.subflow.domain.subscription;

import com.subflow.domain.audit.ActorType;
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
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 구독 변경 이력.
 *
 * <p>플랜·주기·수량·결제일·해지 변경을 <b>해지/재생성 없이</b> 같은 구독 안에서 추적한다.
 * before/after 스냅샷(JSON)을 함께 남겨 "그때 무엇이 어떻게 바뀌었는지"를 재구성할 수 있다.
 */
@Entity
@Table(name = "subscription_change_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionChangeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 40)
    private ChangeType changeType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", nullable = false)
    private String beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", nullable = false)
    private String afterState;

    @Enumerated(EnumType.STRING)
    @Column(name = "effective_mode", nullable = false, length = 20)
    private EffectiveMode effectiveMode;

    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ActorType actorType;

    @Column(name = "actor_id", length = 64)
    private String actorId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private SubscriptionChangeHistory(Subscription subscription, ChangeType changeType,
                                      String beforeState, String afterState,
                                      EffectiveMode effectiveMode, String reason,
                                      ActorType actorType, String actorId) {
        this.subscription = Objects.requireNonNull(subscription, "subscription");
        this.changeType = Objects.requireNonNull(changeType, "changeType");
        this.beforeState = beforeState == null ? "{}" : beforeState;
        this.afterState = afterState == null ? "{}" : afterState;
        this.effectiveMode = Objects.requireNonNull(effectiveMode, "effectiveMode");
        this.reason = reason;
        this.actorType = Objects.requireNonNull(actorType, "actorType");
        this.actorId = actorId;
    }

    public static SubscriptionChangeHistory record(Subscription subscription, ChangeType changeType,
                                                   String beforeState, String afterState,
                                                   EffectiveMode effectiveMode, String reason,
                                                   ActorType actorType, String actorId) {
        return new SubscriptionChangeHistory(subscription, changeType, beforeState, afterState,
                effectiveMode, reason, actorType, actorId);
    }
}

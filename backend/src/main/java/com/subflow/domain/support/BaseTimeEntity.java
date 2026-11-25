package com.subflow.domain.support;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 생성·수정 시각을 공통으로 갖는 엔티티 상위 타입.
 *
 * <p>시각은 모두 UTC 기준 {@link Instant}로 다룬다. 표시 시간대 변환은 API 계층에서 처리한다.
 */
@MappedSuperclass
@Getter
public abstract class BaseTimeEntity {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

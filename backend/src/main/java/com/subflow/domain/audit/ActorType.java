package com.subflow.domain.audit;

/**
 * 행위자 종류. 구독 변경 이력과 감사 로그가 같은 값을 쓴다.
 *
 * <p>{@code actorId} 는 항상 문자열 규격(VARCHAR(64))으로 저장해 BIGINT/UUID 혼용을 막는다.
 */
public enum ActorType {

    /** 서비스 사용자(고객). */
    USER,

    /** 운영자(관리자 콘솔). */
    ADMIN,

    /** 시스템(배치, PG 웹훅, 정합성 보정). */
    SYSTEM;

    public boolean isSystem() {
        return this == SYSTEM;
    }
}

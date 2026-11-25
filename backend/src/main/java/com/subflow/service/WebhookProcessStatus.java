package com.subflow.service;

/** 웹훅 처리 결과. */
public enum WebhookProcessStatus {

    /** 처리 완료(구독 자동 해지 등 부수 효과 반영). */
    PROCESSED,

    /** 같은 이벤트를 이미 처리함 — 200 으로 응답하고 다시 처리하지 않는다. */
    DUPLICATE_IGNORED,

    /** 관심 없는 이벤트 또는 대상 없음 — 기록만 남기고 무시. */
    IGNORED,

    /** 처리 실패 — PG 재전송을 유도한다(500). */
    FAILED
}

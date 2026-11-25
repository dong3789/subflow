package com.subflow.api;

import com.subflow.domain.audit.ActorType;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청 헤더에서 행위자를 읽는다.
 *
 * <p>{@code X-Actor-Type}(USER/ADMIN/SYSTEM, 기본값은 호출 경로에 따라)과 {@code X-Actor-Id} 로
 * "누가 바꿨는지"를 감사 로그·변경 이력에 남긴다. 인증은 API Key 필터가 담당하고, 이 클래스는 표기만 한다.
 */
public final class ActorHeaders {

    public static final String HEADER_ACTOR_TYPE = "X-Actor-Type";
    public static final String HEADER_ACTOR_ID = "X-Actor-Id";

    private ActorHeaders() {
    }

    public record Actor(ActorType type, String id) {
    }

    public static Actor resolve(HttpServletRequest request, ActorType fallbackType) {
        String rawType = request.getHeader(HEADER_ACTOR_TYPE);
        ActorType type = fallbackType;
        if (rawType != null && !rawType.isBlank()) {
            try {
                type = ActorType.valueOf(rawType.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                type = fallbackType;
            }
        }
        String id = request.getHeader(HEADER_ACTOR_ID);
        return new Actor(type, id == null || id.isBlank() ? null : id.trim());
    }
}

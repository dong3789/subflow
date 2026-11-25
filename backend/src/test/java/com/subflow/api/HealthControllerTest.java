package com.subflow.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 헬스 엔드포인트와 글로벌 예외 처리의 응답 형식 검증.
 */
@WebMvcTest(controllers = {HealthController.class, ErrorProbeController.class})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** API Key 필터가 슬라이스에 함께 로드되므로 프로젝트 조회는 목으로 대체한다. */
    @MockitoBean
    private com.subflow.service.ProjectService projectService;

    @Test
    @DisplayName("GET /api/v1/health는 UP 상태와 서비스 정보를 돌려준다")
    void healthReturnsUp() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("subflow-backend"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("없는 리소스는 404와 ENTITY_NOT_FOUND 코드로 응답한다")
    void notFoundMapsTo404() throws Exception {
        mockMvc.perform(get("/api/v1/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENTITY_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/test/missing"));
    }

    @Test
    @DisplayName("허용되지 않는 상태 전이는 409로 응답한다")
    void invalidTransitionMapsTo409() throws Exception {
        mockMvc.perform(get("/api/v1/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("처리하지 못한 예외는 500으로 응답하고 내부 메시지를 노출하지 않는다")
    void unexpectedMapsTo500() throws Exception {
        mockMvc.perform(get("/api/v1/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
    }
}

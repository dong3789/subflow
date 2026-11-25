package com.subflow.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subflow.common.error.ErrorResponse;
import com.subflow.config.SubFlowProperties;
import com.subflow.domain.project.Project;
import com.subflow.service.ProjectService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 프로젝트 API Key 인증 필터.
 *
 * <p>{@code subflow.api-key.required=true} 일 때 {@code X-Api-Key} 헤더로 프로젝트를 식별한다.
 * 웹훅 수신·헬스체크는 PG/모니터링이 직접 호출하므로 인증 대상에서 제외한다.
 * 인증이 필요 없는 기본 구성에서는 요청 속성에 프로젝트만 실어 두고 통과시킨다.
 */
@Component
public class ProjectApiKeyFilter extends OncePerRequestFilter {

    /** 인증된 프로젝트 ID 를 담는 요청 속성 이름. */
    public static final String PROJECT_ID_ATTRIBUTE = "subflow.projectId";

    private final ProjectService projectService;
    private final SubFlowProperties properties;
    private final ObjectMapper objectMapper;

    public ProjectApiKeyFilter(ProjectService projectService, SubFlowProperties properties,
                               ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String headerName = properties.apiKey().headerName();
        String rawKey = request.getHeader(headerName);
        Optional<Project> project = projectService.resolveByApiKey(rawKey);
        project.ifPresent(value -> request.setAttribute(PROJECT_ID_ATTRIBUTE, value.getId()));

        if (properties.apiKey().required() && project.isEmpty() && requiresApiKey(request)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), ErrorResponse.of(
                    HttpStatus.UNAUTHORIZED.value(), "INVALID_API_KEY",
                    "유효한 " + headerName + " 헤더가 필요합니다.", request.getRequestURI()));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean requiresApiKey(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/")
                && !path.startsWith("/api/v1/health")
                && !path.startsWith("/api/v1/webhooks");
    }
}

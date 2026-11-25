package com.subflow.api;

import com.subflow.api.dto.ApiDtos.CreateProjectRequest;
import com.subflow.api.dto.ApiDtos.ProjectResponse;
import com.subflow.domain.project.Project;
import com.subflow.service.ProjectService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로젝트(테넌트) API.
 *
 * <p>프로젝트를 만들면 API Key 원문을 <b>그 응답에서 한 번만</b> 돌려준다. 이후 조회에는 접두사만 나온다.
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request) {
        ProjectService.IssuedProject issued = projectService.create(request.name(), request.webhookUrl(),
                request.webhookSecret());
        return ProjectResponse.of(issued.project(), issued.apiKey());
    }

    @GetMapping
    public List<ProjectResponse> list() {
        return projectService.findAll().stream()
                .map(project -> ProjectResponse.of(project, null))
                .toList();
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable Long projectId) {
        Project project = projectService.get(projectId);
        return ProjectResponse.of(project, null);
    }

    @PatchMapping("/{projectId}/webhook")
    public ProjectResponse updateWebhook(@PathVariable Long projectId,
                                         @RequestParam String webhookUrl,
                                         @RequestParam(required = false) String webhookSecret) {
        return ProjectResponse.of(projectService.updateWebhook(projectId, webhookUrl, webhookSecret), null);
    }
}

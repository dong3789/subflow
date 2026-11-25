package com.subflow.service;

import com.subflow.common.error.BusinessException;
import com.subflow.common.error.EntityNotFoundException;
import com.subflow.domain.project.Project;
import com.subflow.domain.project.ProjectRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로젝트(테넌트) 서비스.
 *
 * <p>API Key 는 발급 시 한 번만 원문을 돌려주고, 저장은 SHA-256 해시만 한다.
 * 화면·로그에는 접두사({@code api_key_prefix})만 노출한다.
 */
@Service
public class ProjectService {

    private static final String KEY_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int KEY_LENGTH = 32;
    private static final int PREFIX_LENGTH = 16;

    private final ProjectRepository projectRepository;
    private final SecureRandom random = new SecureRandom();

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    /**
     * 프로젝트를 만들고 API Key 원문을 함께 돌려준다(이 시점 이후로는 원문을 알 수 없다).
     */
    @Transactional
    public IssuedProject create(String name, String webhookUrl, String webhookSecret) {
        String rawKey = "pk_live_" + randomToken();
        String prefix = rawKey.substring(0, PREFIX_LENGTH);
        Project project = projectRepository.save(
                Project.create(name, prefix, hash(rawKey), webhookUrl, webhookSecret));
        return new IssuedProject(project, rawKey);
    }

    @Transactional
    public Project updateWebhook(Long projectId, String webhookUrl, String webhookSecret) {
        Project project = get(projectId);
        project.updateWebhook(webhookUrl, webhookSecret);
        return project;
    }

    @Transactional(readOnly = true)
    public Project get(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("프로젝트", projectId));
    }

    @Transactional(readOnly = true)
    public List<Project> findAll() {
        return projectRepository.findAll();
    }

    /** API Key 원문으로 프로젝트를 찾는다. 없으면 빈 값(인증 실패). */
    @Transactional(readOnly = true)
    public Optional<Project> resolveByApiKey(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return Optional.empty();
        }
        return projectRepository.findByApiKeyHash(hash(rawApiKey))
                .filter(Project::isActive);
    }

    /** 기본 프로젝트(project_id 가 비어 있는 기존 데이터의 소유자). */
    @Transactional(readOnly = true)
    public Project defaultProject() {
        return projectRepository.findFirstByActiveTrueOrderByIdAsc()
                .orElseThrow(() -> BusinessException.badRequest("PROJECT_NOT_FOUND",
                        "기본 프로젝트가 없습니다. V2 마이그레이션을 확인해 주세요."));
    }

    private String randomToken() {
        StringBuilder builder = new StringBuilder(KEY_LENGTH);
        for (int i = 0; i < KEY_LENGTH; i++) {
            builder.append(KEY_ALPHABET.charAt(random.nextInt(KEY_ALPHABET.length())));
        }
        return builder.toString();
    }

    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다.", e);
        }
    }

    /**
     * 발급 결과.
     *
     * @param project 프로젝트
     * @param apiKey  API Key 원문(이 응답에서만 노출)
     */
    public record IssuedProject(Project project, String apiKey) {
    }
}

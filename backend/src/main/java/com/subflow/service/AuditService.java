package com.subflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subflow.domain.audit.ActorType;
import com.subflow.domain.audit.AuditLog;
import com.subflow.domain.audit.AuditLogRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 기록기.
 *
 * <p>모든 도메인 변경(해지·복구·플랜 변경·환불 자동 해지 등)은 이 서비스를 통해
 * "누가(actor) 무엇을(target) 어떻게 했는지"를 남긴다. 기록은 도메인 변경과 같은 트랜잭션에서 이뤄진다.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AuditLog record(ActorType actorType, String actorId, String targetType, UUID targetId,
                           String action, Map<String, Object> metadata) {
        return auditLogRepository.save(
                AuditLog.record(actorType, actorId, targetType, targetId, action, toJson(metadata)));
    }

    @Transactional
    public AuditLog recordSubscription(ActorType actorType, String actorId, UUID subscriptionPublicId,
                                       String action, Map<String, Object> metadata) {
        return record(actorType, actorId, "SUBSCRIPTION", subscriptionPublicId, action, metadata);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> recent(int limit) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, limit)));
    }

    @Transactional(readOnly = true)
    public List<AuditLog> findByTarget(String targetType, UUID targetId) {
        return auditLogRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId);
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("감사 로그 메타데이터 직렬화에 실패했습니다.", e);
        }
    }
}

package com.subflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subflow.domain.outbox.OutboxEvent;
import com.subflow.domain.outbox.OutboxEventRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional Outbox 적재기.
 *
 * <p>도메인 변경과 <b>같은 트랜잭션</b>으로 이벤트를 적재한다. 실제 발송은
 * {@code OutboxPublisher} 가 별도로 처리하므로, 커밋과 이벤트 발행 사이의 유실·역전이 생기지 않는다.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OutboxEvent enqueue(String aggregateType, String aggregateId, String eventType,
                               Map<String, Object> payload) {
        return outboxEventRepository.save(
                OutboxEvent.pending(aggregateType, aggregateId, eventType, toJson(payload)));
    }

    /**
     * 프로젝트 단위 이벤트를 적재한다. {@code projectId} 는 발행기가 webhook_url 을 찾는 데 쓴다.
     */
    @Transactional
    public OutboxEvent enqueueForProject(Long projectId, String aggregateType, String aggregateId,
                                         String eventType, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (payload != null) {
            body.putAll(payload);
        }
        if (projectId != null) {
            body.put("projectId", projectId);
        }
        return enqueue(aggregateType, aggregateId, eventType, body);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox 페이로드 직렬화에 실패했습니다.", e);
        }
    }
}

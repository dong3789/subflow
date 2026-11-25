package com.subflow.domain.webhook;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookInboxRepository extends JpaRepository<WebhookInbox, Long> {

    Optional<WebhookInbox> findByPgProviderAndEventId(String pgProvider, String eventId);

    boolean existsByPgProviderAndEventId(String pgProvider, String eventId);

    List<WebhookInbox> findByStatusOrderByIdAsc(WebhookInboxStatus status);
}

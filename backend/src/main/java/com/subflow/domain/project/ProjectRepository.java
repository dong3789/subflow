package com.subflow.domain.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByPublicId(java.util.UUID publicId);

    Optional<Project> findByApiKeyHash(String apiKeyHash);

    Optional<Project> findByApiKeyPrefix(String apiKeyPrefix);

    Optional<Project> findFirstByActiveTrueOrderByIdAsc();
}

package io.breland.bbagent.server.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantResponsivenessRepository
    extends JpaRepository<AssistantResponsivenessEntity, String> {}

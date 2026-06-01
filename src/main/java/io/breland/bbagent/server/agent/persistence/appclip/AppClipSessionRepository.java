package io.breland.bbagent.server.agent.persistence.appclip;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppClipSessionRepository extends JpaRepository<AppClipSessionEntity, String> {}

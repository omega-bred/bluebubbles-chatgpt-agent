package io.breland.bbagent.server.agent.persistence.model;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelAccountSettingsRepository
    extends JpaRepository<ModelAccountSettingsEntity, String> {}

package io.breland.bbagent.server.agent.persistence.coder;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CoderOauthCredentialRepository
    extends JpaRepository<CoderOauthCredentialEntity, String> {}

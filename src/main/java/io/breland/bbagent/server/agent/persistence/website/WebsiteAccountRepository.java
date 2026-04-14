package io.breland.bbagent.server.agent.persistence.website;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebsiteAccountRepository extends JpaRepository<WebsiteAccountEntity, String> {}

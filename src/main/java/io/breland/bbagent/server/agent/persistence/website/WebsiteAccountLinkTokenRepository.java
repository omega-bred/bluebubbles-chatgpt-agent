package io.breland.bbagent.server.agent.persistence.website;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebsiteAccountLinkTokenRepository
    extends JpaRepository<WebsiteAccountLinkTokenEntity, String> {}

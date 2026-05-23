package io.breland.bbagent.server.agent.persistence.contact;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebsiteContactMessageRepository
    extends JpaRepository<WebsiteContactMessageEntity, String> {}

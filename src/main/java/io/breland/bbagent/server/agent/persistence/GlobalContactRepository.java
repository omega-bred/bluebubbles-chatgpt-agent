package io.breland.bbagent.server.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GlobalContactRepository extends JpaRepository<GlobalContactEntity, String> {}

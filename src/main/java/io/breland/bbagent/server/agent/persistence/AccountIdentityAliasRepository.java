package io.breland.bbagent.server.agent.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountIdentityAliasRepository
    extends JpaRepository<AccountIdentityAliasEntity, String> {
  List<AccountIdentityAliasEntity> findAllByAliasKeyIn(Collection<String> aliasKeys);

  List<AccountIdentityAliasEntity> findAllByAccountBase(String accountBase);

  List<AccountIdentityAliasEntity> findAllByAccountBaseIn(Collection<String> accountBases);
}

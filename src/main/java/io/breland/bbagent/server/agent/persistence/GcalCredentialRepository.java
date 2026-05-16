package io.breland.bbagent.server.agent.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GcalCredentialRepository extends JpaRepository<GcalCredentialEntity, String> {
  long countByStoreId(String storeId);

  List<GcalCredentialEntity> findAllByStoreId(String storeId);

  List<GcalCredentialEntity> findAllByStoreIdAndAgentAccountId(
      String storeId, String agentAccountId);

  List<GcalCredentialEntity> findAllByStoreIdAndAgentAccountIdIn(
      String storeId, Collection<String> agentAccountIds);

  boolean existsByStoreIdAndAccountKey(String storeId, String accountKey);

  long deleteByStoreIdAndAccountKey(String storeId, String accountKey);

  long deleteAllByStoreId(String storeId);

  @Query("select e.accountKey from GcalCredentialEntity e where e.storeId = :storeId")
  List<String> findAllAccountKeysByStoreId(@Param("storeId") String storeId);

  @Query(
      "select e.googleAccountId from GcalCredentialEntity e where e.storeId = :storeId and e.agentAccountId = :agentAccountId")
  List<String> findGoogleAccountIdsByStoreIdAndAgentAccountId(
      @Param("storeId") String storeId, @Param("agentAccountId") String agentAccountId);
}

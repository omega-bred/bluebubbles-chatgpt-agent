package io.breland.bbagent.server.agent.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GcalCredentialRepository extends JpaRepository<GcalCredentialEntity, String> {
  long countByStoreId(String storeId);

  List<GcalCredentialEntity> findAllByStoreId(String storeId);

  long deleteByStoreIdAndAccountKey(String storeId, String accountKey);

  long deleteAllByStoreId(String storeId);

  @Query("select e.accountKey from GcalCredentialEntity e where e.storeId = :storeId")
  List<String> findAllAccountKeysByStoreId(@Param("storeId") String storeId);
}

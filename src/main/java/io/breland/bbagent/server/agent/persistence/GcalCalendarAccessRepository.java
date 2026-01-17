package io.breland.bbagent.server.agent.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GcalCalendarAccessRepository
    extends JpaRepository<GcalCalendarAccessEntity, String> {
  List<GcalCalendarAccessEntity> findAllByAccountKey(String accountKey);

  long countByAccountKey(String accountKey);

  long deleteByAccountKey(String accountKey);

  @Query("select e.calendarId from GcalCalendarAccessEntity e where e.accountKey = :accountKey")
  List<String> findCalendarIdsByAccountKey(@Param("accountKey") String accountKey);
}

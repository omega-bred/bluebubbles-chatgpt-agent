package io.breland.bbagent.server.agent.persistence.ratelimit;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppRateLimitUsageRepository
    extends JpaRepository<AppRateLimitUsageEntity, String> {

  Optional<AppRateLimitUsageEntity> findByLimitKeyAndScopeTypeAndScopeKeyAndWindowStart(
      String limitKey, String scopeType, String scopeKey, Instant windowStart);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select usage
      from AppRateLimitUsageEntity usage
      where usage.limitKey = :limitKey
        and usage.scopeType = :scopeType
        and usage.scopeKey = :scopeKey
        and usage.windowStart = :windowStart
      """)
  Optional<AppRateLimitUsageEntity> findForUpdate(
      @Param("limitKey") String limitKey,
      @Param("scopeType") String scopeType,
      @Param("scopeKey") String scopeKey,
      @Param("windowStart") Instant windowStart);

  @Query(
      """
      select usage
      from AppRateLimitUsageEntity usage
      where usage.limitKey = :limitKey
        and usage.scopeType = :scopeType
        and usage.windowStart = :windowStart
      order by usage.amount desc, usage.scopeKey asc
      """)
  List<AppRateLimitUsageEntity> findUsageForWindow(
      @Param("limitKey") String limitKey,
      @Param("scopeType") String scopeType,
      @Param("windowStart") Instant windowStart,
      Pageable pageable);
}

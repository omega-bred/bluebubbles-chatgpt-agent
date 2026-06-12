package io.breland.bbagent.server;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.lang.Nullable;

public final class TimeSupport {
  private TimeSupport() {}

  public static @Nullable OffsetDateTime offset(@Nullable Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}

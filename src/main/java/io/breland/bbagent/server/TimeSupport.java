package io.breland.bbagent.server;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.lang.Nullable;

public final class TimeSupport {
  private static final long EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L;

  private TimeSupport() {}

  public static @Nullable OffsetDateTime offset(@Nullable Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  public static @Nullable Instant epochSecondsOrMillis(@Nullable Long value) {
    if (value == null) {
      return null;
    }
    return value > EPOCH_MILLIS_THRESHOLD
        ? Instant.ofEpochMilli(value)
        : Instant.ofEpochSecond(value);
  }

  public static Instant epochSecondsOrMillisOrNow(@Nullable Long value) {
    Instant instant = epochSecondsOrMillis(value);
    return instant == null ? Instant.now() : instant;
  }
}

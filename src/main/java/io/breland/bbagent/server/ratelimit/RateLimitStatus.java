package io.breland.bbagent.server.ratelimit;

import java.time.Instant;

public record RateLimitStatus(
    String limitKey,
    String limitLabel,
    String scopeType,
    String scopeKey,
    long used,
    long limit,
    Instant windowStart,
    Instant windowEnd) {

  public long remaining() {
    return Math.max(0, limit - used);
  }

  public boolean exhausted() {
    return remaining() <= 0;
  }

  public double percentage() {
    if (limit <= 0) {
      return 1.0;
    }
    return Math.min(1.0, (double) used / limit);
  }
}

package io.breland.bbagent.server.ratelimit;

import java.time.Instant;

public record RateLimitPolicy(
    String limitKey,
    String limitLabel,
    String scopeType,
    String scopeKey,
    long limit,
    Instant windowStart,
    Instant windowEnd) {}

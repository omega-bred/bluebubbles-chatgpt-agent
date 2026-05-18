package io.breland.bbagent.server.ratelimit;

public record RateLimitDecision(RateLimitStatus status, boolean allowed, long requestedAmount) {}

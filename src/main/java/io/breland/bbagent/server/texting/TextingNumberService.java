package io.breland.bbagent.server.texting;

import io.breland.bbagent.generated.model.TextingNumberResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TextingNumberService {
  private final String phoneNumberE164;
  private final String displayNumber;
  private final String defaultMessage;
  private final int rateLimitRps;
  private final Clock clock;
  private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

  @Autowired
  public TextingNumberService(
      @Value("${bluechat.texting.phone-number-e164:+14158674956}") String phoneNumberE164,
      @Value("${bluechat.texting.display-number:+1 (415) 867-4956}") String displayNumber,
      @Value("${bluechat.texting.default-message:Hi BlueChatAI, let's start.}")
          String defaultMessage,
      @Value("${bluechat.texting.public-rate-limit-rps:5}") int rateLimitRps) {
    this(phoneNumberE164, displayNumber, defaultMessage, rateLimitRps, Clock.systemUTC());
  }

  TextingNumberService(
      String phoneNumberE164,
      String displayNumber,
      String defaultMessage,
      int rateLimitRps,
      Clock clock) {
    this.phoneNumberE164 = normalizeE164(phoneNumberE164);
    this.displayNumber =
        StringUtils.defaultIfBlank(StringUtils.trimToNull(displayNumber), this.phoneNumberE164);
    this.defaultMessage =
        StringUtils.defaultIfBlank(
            StringUtils.trimToNull(defaultMessage), "Hi BlueChatAI, let's start.");
    this.rateLimitRps = Math.max(1, rateLimitRps);
    this.clock = clock;
  }

  public TextingNumberResponse getPublicNumber(HttpServletRequest request) {
    enforceRateLimit(clientKey(request));
    return response(defaultMessage);
  }

  public TextingNumberResponse response(String message) {
    String resolvedMessage =
        StringUtils.defaultIfBlank(StringUtils.trimToNull(message), defaultMessage);
    return new TextingNumberResponse()
        .phoneNumberE164(phoneNumberE164)
        .displayNumber(displayNumber)
        .defaultMessage(resolvedMessage)
        .smsUrl(smsUrl(phoneNumberE164, resolvedMessage));
  }

  private void enforceRateLimit(String key) {
    long window = clock.millis() / 1_000L;
    WindowCounter counter =
        counters.compute(
            key,
            (ignored, existing) -> {
              if (existing == null || existing.windowSecond() != window) {
                return new WindowCounter(window, 1);
              }
              return new WindowCounter(window, existing.count() + 1);
            });
    if (counter.count() > rateLimitRps) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
    }
    if (counters.size() > 10_000) {
      prune(window);
    }
  }

  private void prune(long currentWindow) {
    counters.entrySet().removeIf(entry -> entry.getValue().windowSecond() < currentWindow - 10);
  }

  private String clientKey(HttpServletRequest request) {
    if (request == null) {
      return "unknown";
    }
    String forwardedFor = firstHeaderValue(request.getHeader("X-Forwarded-For"));
    if (StringUtils.isNotBlank(forwardedFor)) {
      return forwardedFor;
    }
    String realIp = firstHeaderValue(request.getHeader("X-Real-IP"));
    if (StringUtils.isNotBlank(realIp)) {
      return realIp;
    }
    return StringUtils.defaultIfBlank(request.getRemoteAddr(), "unknown");
  }

  private String firstHeaderValue(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    String first = value.split(",", 2)[0];
    return StringUtils.trimToNull(first);
  }

  private String smsUrl(String phoneNumber, String message) {
    return "sms:"
        + phoneNumber
        + "&body="
        + URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private String normalizeE164(String value) {
    String trimmed = StringUtils.trimToNull(value);
    if (trimmed == null) {
      return "+14158674956";
    }
    return trimmed.replaceAll("[^+0-9]", "");
  }

  private record WindowCounter(long windowSecond, int count) {}
}

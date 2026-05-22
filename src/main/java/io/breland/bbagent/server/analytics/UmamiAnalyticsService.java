package io.breland.bbagent.server.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class UmamiAnalyticsService {
  private static final int MAX_EVENT_NAME_LENGTH = 50;
  private static final int MAX_STRING_LENGTH = 500;
  private static final int MAX_DATA_PROPERTIES = 50;

  private final UmamiAnalyticsProperties properties;
  private final WebClient webClient;

  public UmamiAnalyticsService(
      UmamiAnalyticsProperties properties, WebClient.Builder webClientBuilder) {
    this.properties = properties;
    this.webClient = webClientBuilder.baseUrl(normalizedHostUrl(properties.getHostUrl())).build();
  }

  public void track(String eventName, String path) {
    track(eventName, path, null, Map.of());
  }

  public void track(
      String eventName, String path, @Nullable String distinctKey, @Nullable Map<String, ?> data) {
    UmamiSendRequest request = buildRequest(eventName, path, distinctKey, data);
    if (request == null) {
      return;
    }

    webClient
        .post()
        .uri("/api/send")
        .contentType(MediaType.APPLICATION_JSON)
        .header(
            HttpHeaders.USER_AGENT,
            firstNonBlank(properties.getUserAgent(), "Mozilla/5.0 (Server)"))
        .bodyValue(request)
        .retrieve()
        .toBodilessEntity()
        .doOnError(
            error ->
                log.debug(
                    "Failed to send Umami analytics event {}", request.payload().name(), error))
        .onErrorResume(error -> Mono.empty())
        .subscribe();
  }

  @Nullable
  UmamiSendRequest buildRequest(
      String eventName, String path, @Nullable String distinctKey, @Nullable Map<String, ?> data) {
    if (!properties.isEnabled() || StringUtils.isBlank(properties.getWebsiteId())) {
      return null;
    }
    String name = truncate(firstNonBlank(eventName, "server_event"), MAX_EVENT_NAME_LENGTH);
    Map<String, Object> cleanedData = cleanData(data);
    UmamiPayload payload =
        new UmamiPayload(
            firstNonBlank(properties.getHostname(), "server"),
            firstNonBlank(properties.getLanguage(), "en-US"),
            "",
            normalizePath(path),
            name,
            title(name),
            properties.getWebsiteId().trim(),
            distinctId(distinctKey),
            cleanedData.isEmpty() ? null : cleanedData);
    return new UmamiSendRequest("event", payload);
  }

  private Map<String, Object> cleanData(@Nullable Map<String, ?> data) {
    if (data == null || data.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> cleaned = new LinkedHashMap<>();
    for (Map.Entry<String, ?> entry : data.entrySet()) {
      if (cleaned.size() >= MAX_DATA_PROPERTIES || StringUtils.isBlank(entry.getKey())) {
        continue;
      }
      Object value = cleanValue(entry.getValue());
      if (value != null) {
        cleaned.put(truncate(entry.getKey(), MAX_STRING_LENGTH), value);
      }
    }
    return cleaned;
  }

  @Nullable
  private Object cleanValue(@Nullable Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
      return value;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    return truncate(String.valueOf(value), MAX_STRING_LENGTH);
  }

  private String title(String name) {
    return "server " + name;
  }

  private String normalizePath(String path) {
    String cleanPath = StringUtils.trimToNull(path);
    if (cleanPath == null) {
      return "/server";
    }
    int queryIndex = cleanPath.indexOf('?');
    if (queryIndex >= 0) {
      cleanPath = cleanPath.substring(0, queryIndex);
    }
    if (!cleanPath.startsWith("/")) {
      cleanPath = "/" + cleanPath;
    }
    return cleanPath;
  }

  private String normalizedHostUrl(String hostUrl) {
    String cleanHostUrl = firstNonBlank(hostUrl, "https://unami.bre.land");
    while (cleanHostUrl.endsWith("/")) {
      cleanHostUrl = cleanHostUrl.substring(0, cleanHostUrl.length() - 1);
    }
    return cleanHostUrl;
  }

  @Nullable
  private String distinctId(@Nullable String distinctKey) {
    String cleanKey = StringUtils.trimToNull(distinctKey);
    if (cleanKey == null) {
      return null;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(cleanKey.getBytes(StandardCharsets.UTF_8)))
          .substring(0, 50);
    } catch (NoSuchAlgorithmException e) {
      return Integer.toHexString(cleanKey.toLowerCase(Locale.ROOT).hashCode());
    }
  }

  private String truncate(String value, int maxLength) {
    return value.length() > maxLength ? value.substring(0, maxLength) : value;
  }

  private String firstNonBlank(String... values) {
    return StringUtils.trimToNull(StringUtils.firstNonBlank(values));
  }

  record UmamiSendRequest(String type, UmamiPayload payload) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record UmamiPayload(
      String hostname,
      String language,
      String referrer,
      String url,
      String name,
      String title,
      String website,
      @Nullable String id,
      @Nullable Map<String, Object> data) {}
}

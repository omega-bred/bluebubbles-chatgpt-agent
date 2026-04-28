package io.breland.bbagent.server.agent.location;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@Slf4j
public class NominatimReverseLocationLookup implements ReverseLocationLookup {
  private static final String USER_AGENT = "bluebubbles-chatgpt-agent";

  private final String baseUrl;
  private final Duration timeout;
  private final int zoom;
  private final WebClient webClient;

  @Autowired
  public NominatimReverseLocationLookup(
      @Value("${location.reverse-lookup.nominatim.base-url:}") String baseUrl,
      @Value("${location.reverse-lookup.nominatim.timeout-seconds:2}") long timeoutSeconds,
      @Value("${location.reverse-lookup.nominatim.zoom:18}") int zoom) {
    this(baseUrl, timeoutSeconds, zoom, createWebClient(baseUrl));
  }

  NominatimReverseLocationLookup(
      String baseUrl, long timeoutSeconds, int zoom, WebClient webClient) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    this.zoom = Math.max(0, Math.min(18, zoom));
    this.webClient = webClient;
  }

  private static WebClient createWebClient(String baseUrl) {
    WebClient.Builder builder = WebClient.builder().defaultHeader("User-Agent", USER_AGENT);
    if (baseUrl != null && !baseUrl.isBlank()) {
      builder.baseUrl(baseUrl.trim());
    }
    return builder.build();
  }

  @Override
  public Optional<ReverseLocationLookupResult> reverseLookup(double latitude, double longitude) {
    if (baseUrl.isBlank()) {
      return Optional.empty();
    }
    if (!isValidCoordinate(latitude, longitude)) {
      log.warn("Skipping reverse location lookup for invalid coordinates");
      return Optional.empty();
    }

    try {
      JsonNode response =
          webClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/reverse")
                          .queryParam("format", "jsonv2")
                          .queryParam("lat", latitude)
                          .queryParam("lon", longitude)
                          .queryParam("zoom", zoom)
                          .queryParam("addressdetails", 1)
                          .build())
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .bodyToMono(JsonNode.class)
              .block(timeout);
      return parseResponse(response);
    } catch (Exception e) {
      log.warn("Reverse location lookup failed: {}", e.toString());
      log.debug("Reverse location lookup failure details", e);
      return Optional.empty();
    }
  }

  private static boolean isValidCoordinate(double latitude, double longitude) {
    return !Double.isNaN(latitude)
        && !Double.isInfinite(latitude)
        && !Double.isNaN(longitude)
        && !Double.isInfinite(longitude)
        && latitude >= -90
        && latitude <= 90
        && longitude >= -180
        && longitude <= 180;
  }

  private static Optional<ReverseLocationLookupResult> parseResponse(JsonNode response) {
    if (response == null || response.isNull() || response.hasNonNull("error")) {
      return Optional.empty();
    }
    String displayName = textValue(response, "display_name");
    Map<String, String> address = parseAddress(response.get("address"));
    ReverseLocationLookupResult result = new ReverseLocationLookupResult(displayName, address);
    String approximateAddress = result.approximateAddress();
    if (approximateAddress == null || approximateAddress.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(result);
  }

  private static Map<String, String> parseAddress(JsonNode addressNode) {
    Map<String, String> address = new LinkedHashMap<>();
    if (addressNode == null || !addressNode.isObject()) {
      return address;
    }
    addressNode
        .fields()
        .forEachRemaining(
            entry -> {
              if (entry.getValue() != null && entry.getValue().isTextual()) {
                String value = entry.getValue().asText();
                if (value != null && !value.isBlank()) {
                  address.put(entry.getKey(), value);
                }
              }
            });
    return address;
  }

  private static String textValue(JsonNode node, String fieldName) {
    JsonNode value = node.get(fieldName);
    if (value == null || !value.isTextual()) {
      return null;
    }
    String text = value.asText();
    return text == null || text.isBlank() ? null : text;
  }
}

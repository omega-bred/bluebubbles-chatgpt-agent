package io.breland.bbagent.server.agent.tools.giphy;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.handler.logging.LogLevel;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

@Component
@Slf4j
public class GiphyClient {
  private static final Duration API_TIMEOUT = Duration.of(10, ChronoUnit.SECONDS);
  private static final Duration DOWNLOAD_TIMEOUT = Duration.of(20, ChronoUnit.SECONDS);
  private static final int MAX_IN_MEMORY_BYTES = 20 * 1024 * 1024;

  private final String apiKey;
  private final WebClient webClient;

  public record GiphyGif(String id, String title, String url, String stillUrl) {}

  public GiphyClient(
      @Value("${giphy.apiKey:}") String apiKey,
      @Value("${giphy.baseUrl:https://api.giphy.com/v1}") String baseUrl) {
    this.apiKey = apiKey;
    ExchangeStrategies strategies =
        ExchangeStrategies.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
            .build();
    HttpClient httpClient =
        HttpClient.create()
            .wiretap(
                "reactor.netty.http.client.HttpClient",
                LogLevel.INFO,
                AdvancedByteBufFormat.SIMPLE);
    this.webClient =
        WebClient.builder()
            .baseUrl(baseUrl)
            .exchangeStrategies(strategies)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
  }

  public Optional<GiphyGif> searchTopGif(String query) {
    List<GiphyGif> gifs = searchGifs(query, 5, null, "en");
    if (gifs.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(gifs.get(0));
  }

  public List<GiphyGif> searchGifs(String query, int limit, String rating, String lang) {
    if (apiKey == null || apiKey.isBlank()) {
      log.warn("Giphy API key not configured");
      return Collections.emptyList();
    }
    if (query == null || query.isBlank()) {
      return Collections.emptyList();
    }
    int safeLimit = Math.max(1, Math.min(limit, 25));
    String safeLang = (lang == null || lang.isBlank()) ? "en" : lang;
    try {
      JsonNode response =
          webClient
              .get()
              .uri(
                  uriBuilder ->
                      buildSearchUri(uriBuilder, query, safeLimit, rating, safeLang, apiKey))
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .bodyToMono(JsonNode.class)
              .onErrorResume(WebClientResponseException.class, err -> Mono.empty())
              .block(API_TIMEOUT);
      return parseGifs(response);
    } catch (Exception e) {
      log.warn("Failed to search giphy for {}", query, e);
      return Collections.emptyList();
    }
  }

  public Optional<byte[]> downloadGifBytes(String url) {
    if (url == null || url.isBlank()) {
      return Optional.empty();
    }
    try {
      byte[] bytes =
          webClient
              .get()
              .uri(url)
              .accept(MediaType.ALL)
              .retrieve()
              .bodyToMono(byte[].class)
              .block(DOWNLOAD_TIMEOUT);
      if (bytes == null || bytes.length == 0) {
        return Optional.empty();
      }
      return Optional.of(bytes);
    } catch (Exception e) {
      log.warn("Failed to download giphy {}", url, e);
      return Optional.empty();
    }
  }

  private static java.net.URI buildSearchUri(
      UriBuilder uriBuilder, String query, int limit, String rating, String lang, String apiKey) {
    UriBuilder builder =
        uriBuilder
            .path("/gifs/search")
            .queryParam("api_key", apiKey)
            .queryParam("q", query)
            .queryParam("limit", limit)
            .queryParam("offset", 0)
            .queryParam("bundle", "messaging_non_clips");
    if (rating != null && !rating.isBlank()) {
      builder.queryParam("rating", rating);
    }
    if (lang != null && !lang.isBlank()) {
      builder.queryParam("lang", lang);
    }
    return builder.build();
  }

  private List<GiphyGif> parseGifs(JsonNode response) {
    if (response == null || response.isNull()) {
      return Collections.emptyList();
    }
    JsonNode data = response.path("data");
    if (!data.isArray()) {
      return Collections.emptyList();
    }
    List<GiphyGif> results = new ArrayList<>();
    for (JsonNode item : data) {
      if (item == null || item.isNull()) {
        continue;
      }
      String id = item.path("id").asText(null);
      String title = item.path("title").asText("");
      JsonNode images = item.path("images");
      String url = null;
      if (images.hasNonNull("original")) {
        url = images.path("original").path("url").asText(null);
      }
      if ((url == null || url.isBlank()) && images.hasNonNull("fixed_width")) {
        url = images.path("fixed_width").path("url").asText(null);
      }
      if (url == null || url.isBlank()) {
        continue;
      }
      String stillUrl = null;
      if (images.hasNonNull("fixed_width_still")) {
        stillUrl = images.path("fixed_width_still").path("url").asText(null);
      }
      if ((stillUrl == null || stillUrl.isBlank()) && images.hasNonNull("downsized_still")) {
        stillUrl = images.path("downsized_still").path("url").asText(null);
      }
      results.add(new GiphyGif(id, title, url, stillUrl));
    }
    return results;
  }
}

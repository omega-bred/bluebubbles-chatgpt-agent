package io.breland.bbagent.server.agent.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Hindsight v0.10 document API. Bank IDs are supplied only by application authorization. */
@Component
@Slf4j
public class HindsightClient {
  private static final String RETENTION =
      " Retain only durable context, meaningful changes, shared constraints and actionable commitments with concrete future usefulness. Omit routine updates, isolated observations and repeated details without lasting consequences. Truth and repetition alone do not justify retention. Preserve uncertainty. Retaining no facts is a successful outcome.";
  private static final ObjectMapper JSON = new ObjectMapper();
  private final WebClient client;
  private final boolean configured;
  private final Duration timeout;
  private final int recallTokens;

  public record RecalledMemory(String documentId, String text, String contentHash) {}

  public HindsightClient(
      @Value("${hindsight.enabled:false}") boolean enabled,
      @Value("${hindsight.base-url:}") String baseUrl,
      @Value("${hindsight.api-key:}") String apiKey,
      @Value("${hindsight.request-timeout:PT10S}") Duration timeout,
      @Value("${hindsight.recall-max-tokens:4096}") int recallTokens) {
    this.configured = enabled && StringUtils.isNotBlank(baseUrl);
    this.timeout = Duration.ofMillis(Math.clamp(timeout.toMillis(), 1000, 15000));
    this.recallTokens = Math.clamp(recallTokens, 256, 16384);
    WebClient.Builder builder = WebClient.builder().baseUrl(StringUtils.defaultString(baseUrl));
    if (StringUtils.isNotBlank(apiKey))
      builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
    client = builder.build();
  }

  public boolean isConfigured() {
    return configured;
  }

  public boolean configureBank(String bankId, boolean group) {
    if (!configured) return false;
    String mission =
        (group
                ? "Remember shared group context. Do not infer an individual's preferences as collective facts."
                : "Remember useful context about this account, without inventing preferences or consent.")
            + RETENTION;
    try {
      client
          .put()
          .uri("/v1/default/banks/{bank}", bankId)
          .bodyValue(
              Map.of(
                  "retain_mission",
                  mission,
                  "observations_mission",
                  mission,
                  "retain_extraction_mode",
                  "concise"))
          .retrieve()
          .toBodilessEntity()
          .block(timeout);
      return true;
    } catch (RuntimeException e) {
      report("configure", e);
      return false;
    }
  }

  public boolean retain(
      String bankId,
      String documentId,
      String operationId,
      String text,
      String hash,
      Instant occurredAt) {
    if (!configured) return false;
    try {
      JsonNode response =
          client
              .post()
              .uri("/v1/default/banks/{bank}/memories", bankId)
              .bodyValue(
                  Map.of(
                      "async",
                      true,
                      "operation_id",
                      operationId,
                      "items",
                      List.of(
                          Map.of(
                              "document_id",
                              documentId,
                              "content",
                              text,
                              "timestamp",
                              occurredAt.toString(),
                              "update_mode",
                              "replace",
                              "metadata",
                              Map.of("source", "bluechat", "content_hash", hash)))))
              .retrieve()
              .bodyToMono(String.class)
              .map(this::parseJson)
              .block(timeout);
      return response != null
          && response.path("success").asBoolean()
          && operationId.equals(response.path("operation_id").asText());
    } catch (RuntimeException e) {
      report("retain", e);
      return false;
    }
  }

  public String operationStatus(String bankId, String operationId) {
    if (!configured) return "unavailable";
    try {
      JsonNode response =
          client
              .get()
              .uri("/v1/default/banks/{bank}/operations/{operation}", bankId, operationId)
              .retrieve()
              .bodyToMono(String.class)
              .map(this::parseJson)
              .block(timeout);
      return response == null ? "unavailable" : response.path("status").asText("unavailable");
    } catch (WebClientResponseException.NotFound e) {
      return "not_found";
    } catch (RuntimeException e) {
      report("status", e);
      return "unavailable";
    }
  }

  public List<RecalledMemory> recall(String bankId, String query) {
    if (!configured || StringUtils.isBlank(query)) return List.of();
    try {
      JsonNode response =
          client
              .post()
              .uri("/v1/default/banks/{bank}/memories/recall", bankId)
              .bodyValue(
                  Map.of(
                      "query",
                      query,
                      "types",
                      List.of("world", "experience"),
                      "budget",
                      "mid",
                      "max_tokens",
                      recallTokens))
              .retrieve()
              .bodyToMono(String.class)
              .map(this::parseJson)
              .block(timeout);
      List<RecalledMemory> results = new ArrayList<>();
      if (response != null)
        for (JsonNode item : response.path("results")) {
          if (!List.of("world", "experience").contains(item.path("type").asText())) continue;
          String id = item.path("document_id").asText();
          String text = item.path("text").asText();
          String hash = item.path("metadata").path("content_hash").asText();
          if (StringUtils.isNoneBlank(id, text, hash))
            results.add(new RecalledMemory(id, text, hash));
        }
      return List.copyOf(results);
    } catch (WebClientResponseException.NotFound e) {
      return List.of();
    } catch (RuntimeException e) {
      report("recall", e);
      return List.of();
    }
  }

  public boolean deleteDocument(String bankId, String documentId) {
    if (!configured) return false;
    try {
      client
          .delete()
          .uri("/v1/default/banks/{bank}/documents/{document}", bankId, documentId)
          .retrieve()
          .toBodilessEntity()
          .block(timeout);
      // Verify the source document is gone before acknowledging deletion.
      client
          .get()
          .uri("/v1/default/banks/{bank}/documents/{document}", bankId, documentId)
          .retrieve()
          .toBodilessEntity()
          .block(timeout);
      return false;
    } catch (WebClientResponseException.NotFound e) {
      return true;
    } catch (RuntimeException e) {
      report("delete", e);
      return false;
    }
  }

  private JsonNode parseJson(String body) {
    try {
      return JSON.readTree(body);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Invalid Hindsight JSON response");
    }
  }

  private void report(String operation, RuntimeException e) {
    // Exception messages/bodies can contain source content or credentials.
    log.warn(
        "Hindsight {} failed ({})",
        operation,
        e instanceof WebClientResponseException response
            ? response.getStatusCode().value()
            : e.getClass().getSimpleName());
  }
}

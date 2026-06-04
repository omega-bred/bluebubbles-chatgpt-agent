package io.breland.bbagent.server.agent.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
@Component
public class Mem0Client {

  private static final Duration API_TIMEOUT = Duration.ofSeconds(10);

  private final WebClient webClient;
  private final String orgId;
  private final String projectId;
  @Getter private final boolean configured;
  @Getter private final ObjectMapper objectMapper;

  public Mem0Client(
      @Value("${mem0.base-url}") String baseUrl,
      @Value("${mem0.api-key}") String apiKey,
      @Value("${mem0.org-id:}") String orgId,
      @Value("${mem0.project-id:}") String projectId,
      ObjectMapper objectMapper) {
    this.orgId = orgId;
    this.projectId = projectId;
    this.objectMapper = objectMapper;
    this.configured = apiKey != null && !apiKey.isBlank() && !"fake_key".equals(apiKey);
    this.webClient =
        WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Token " + apiKey)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .filter(
                (request, next) -> {
                  log.debug("Mem0 request {} {}", request.method(), request.url());
                  return next.exchange(request)
                      .doOnNext(response -> log.debug("Mem0 response {}", response.statusCode()));
                })
            .build();
    if (!configured) {
      log.warn("Mem0 client not configured; set mem0.api-key to enable memory");
    }
  }

  public record StoredMemory(String memoryId, String memory) {}

  public boolean addMemory(String userId, String memory, Map<String, Object> metadata) {
    if (!configured) {
      return false;
    }
    if (userId == null || userId.isBlank() || memory == null || memory.isBlank()) {
      return false;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("user_id", userId);
    body.put("version", "v2");
    body.put("messages", List.of(Map.of("role", "user", "content", memory)));
    if (metadata != null && !metadata.isEmpty()) {
      body.put("metadata", metadata);
    }
    applyWorkspace(body);
    try {
      webClient
          .post()
          .uri("/v1/memories/")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(body)
          .retrieve()
          .bodyToMono(JsonNode.class)
          .block(API_TIMEOUT);
      return true;
    } catch (WebClientResponseException e) {
      log.warn("Mem0 add memory failed: status={}", e.getStatusCode());
      return false;
    } catch (Exception e) {
      log.warn("Mem0 add memory failed", e);
      return false;
    }
  }

  public List<StoredMemory> searchMemories(String userId, String query) {
    if (!configured) {
      return List.of();
    }
    if (userId == null || userId.isBlank() || query == null || query.isBlank()) {
      return List.of();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    body.put("filters", Map.of("user_id", userId));
    body.put("version", "v2");
    body.put("top_k", 5);
    applyWorkspace(body);
    try {
      JsonNode response =
          webClient
              .post()
              .uri("/v2/memories/search/")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(body)
              .retrieve()
              .bodyToMono(JsonNode.class)
              .block(API_TIMEOUT);
      if (response == null || response.isNull()) {
        return List.of();
      }
      JsonNode results = response.has("results") ? response.get("results") : response;
      if (results == null || !results.isArray()) {
        return List.of();
      }
      List<StoredMemory> memories = new ArrayList<>();
      for (JsonNode item : results) {
        if (item == null || item.isNull()) {
          continue;
        }
        String memoryId = null;
        String memory = null;
        JsonNode idNode = item.get("id");
        if (idNode != null && !idNode.isNull()) {
          memoryId = idNode.asText();
        } else {
          JsonNode memoryIdNode = item.get("memory_id");
          if (memoryIdNode != null && !memoryIdNode.isNull()) {
            memoryId = memoryIdNode.asText();
          }
        }
        JsonNode memoryNode = item.get("memory");
        if (memoryNode != null && !memoryNode.isNull()) {
          memory = memoryNode.asText();
        } else {
          JsonNode dataNode = item.get("data");
          if (dataNode != null && !dataNode.isNull()) {
            JsonNode dataMemory = dataNode.get("memory");
            if (dataMemory != null && !dataMemory.isNull()) {
              memory = dataMemory.asText();
            }
          }
        }
        if (memoryId == null && memory == null) {
          continue;
        }
        memories.add(new StoredMemory(memoryId, memory));
      }
      return memories;
    } catch (WebClientResponseException e) {
      log.warn("Mem0 search memories failed: status={}", e.getStatusCode());
      return List.of();
    } catch (Exception e) {
      log.warn("Mem0 search memories failed", e);
      return List.of();
    }
  }

  private void applyWorkspace(Map<String, Object> body) {
    if (orgId != null && !orgId.isBlank()) {
      body.put("org_id", orgId);
    }
    if (projectId != null && !projectId.isBlank()) {
      body.put("project_id", projectId);
    }
  }

  public boolean updateMemory(String memoryId, String text, Map<String, Object> metadata) {
    if (!configured) {
      return false;
    }
    if (memoryId == null || memoryId.isBlank() || text == null || text.isBlank()) {
      return false;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("text", text);
    if (metadata != null && !metadata.isEmpty()) {
      body.put("metadata", metadata);
    }
    applyWorkspace(body);
    try {
      webClient
          .put()
          .uri("/v1/memories/{memoryId}/", memoryId)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(body)
          .retrieve()
          .bodyToMono(JsonNode.class)
          .block(API_TIMEOUT);
      return true;
    } catch (WebClientResponseException e) {
      log.warn("Mem0 update memory failed: status={}", e.getStatusCode());
      return false;
    } catch (Exception e) {
      log.warn("Mem0 update memory failed", e);
      return false;
    }
  }

  public boolean deleteMemory(String memoryId) {
    if (!configured) {
      return false;
    }
    if (memoryId == null || memoryId.isBlank()) {
      return false;
    }
    log.info("Mem0 deleteMemory DELETE /v1/memories/{}/", memoryId);
    try {
      webClient
          .delete()
          .uri("/v1/memories/{memoryId}/", memoryId)
          .retrieve()
          .bodyToMono(JsonNode.class)
          .block(API_TIMEOUT);
      return true;
    } catch (WebClientResponseException e) {
      log.warn("Mem0 delete memory failed: status={}", e.getStatusCode());
      return false;
    } catch (Exception e) {
      log.warn("Mem0 delete memory failed", e);
      return false;
    }
  }
}

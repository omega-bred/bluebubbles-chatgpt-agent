package io.breland.bbagent.server.agent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class Mem0ClientTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void addMemoryReturnsTheCreatedMemoryId() throws IOException {
    AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/memories/",
        exchange -> {
          requestBody.set(
              mapper.readTree(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          byte[] response =
              "[{\"id\":\"memory-1\",\"memory\":\"likes tea\",\"event\":\"ADD\"}]"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      Mem0Client client =
          new Mem0Client(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "test-key",
              "org-1",
              "project-1",
              mapper);

      Mem0Client.MemoryMutationResult result =
          client.addMemory("account:account-1", "likes tea", Map.of("source", "bbagent"));

      assertThat(result.success()).isTrue();
      assertThat(result.memoryId()).isEqualTo("memory-1");
      assertThat(requestBody.get().path("user_id").asText()).isEqualTo("account:account-1");
      assertThat(requestBody.get().path("org_id").asText()).isEqualTo("org-1");
      assertThat(requestBody.get().path("project_id").asText()).isEqualTo("project-1");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void groupProjectionUsesCanonicalScopeAndOpaqueMetadata() throws IOException {
    AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/memories/",
        exchange -> {
          requestBody.set(
              mapper.readTree(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          byte[] response =
              "{\"results\":[{\"memory_id\":\"projected-1\"}]}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      Mem0Client client =
          new Mem0Client(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "test-key",
              "org-1",
              "project-1",
              mapper);

      Mem0Client.MemoryMutationResult result =
          client.addMemory(
              "account:account-1",
              "Collective group decision (Trip, 2026-08-08): Meet Saturday.",
              Map.of(
                  "artifact_id", "artifact-1",
                  "conversation_id", "conversation-1",
                  "kind", "GROUP_DECISION",
                  "occurred_at", "2026-08-08T17:03:00Z",
                  "source", "bbagent_group_memory"));

      assertThat(result).isEqualTo(new Mem0Client.MemoryMutationResult(true, "projected-1"));
      assertThat(requestBody.get().path("user_id").asText()).isEqualTo("account:account-1");
      assertThat(requestBody.get().path("metadata").fieldNames())
          .toIterable()
          .containsExactlyInAnyOrder(
              "artifact_id", "conversation_id", "kind", "occurred_at", "source");
      assertThat(requestBody.get().path("metadata").toString())
          .doesNotContain("account-1", "iMessage", "+1555", "Meet Saturday");
    } finally {
      server.stop(0);
    }
  }
}

package io.breland.bbagent.server.agent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class HindsightClientTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void sendsDocumentContractAndRecallsOnlyProvenancedSourceFacts() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    List<String> bodies = new CopyOnWriteArrayList<>();
    List<String> methods = new CopyOnWriteArrayList<>();
    String operation = UUID.randomUUID().toString();
    server.createContext(
        "/",
        exchange -> {
          methods.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String path = exchange.getRequestURI().getPath();
          String response =
              path.endsWith("recall")
                  ? """
          {"results":[
            {"document_id":"doc","text":"Likes tea","type":"world","metadata":{"content_hash":"hash"}},
            {"document_id":"doc","text":"inference","type":"observation","metadata":{"content_hash":"hash"}},
            {"text":"no source","type":"world"}]}
          """
                  : path.endsWith(operation)
                      ? "{\"status\":\"completed\"}"
                      : "{\"success\":true,\"operation_id\":\"" + operation + "\"}";
          int status =
              path.contains("/documents/") && exchange.getRequestMethod().equals("GET") ? 404 : 200;
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      HindsightClient client =
          new HindsightClient(
              true,
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "test-token",
              Duration.ofSeconds(3),
              4096);
      assertThat(client.configureBank("bank", true)).isTrue();
      assertThat(client.retain("bank", "doc", operation, "Likes tea", "hash", Instant.EPOCH))
          .isTrue();
      assertThat(client.operationStatus("bank", operation)).isEqualTo("completed");
      assertThat(client.recall("bank", "tea"))
          .containsExactly(new HindsightClient.RecalledMemory("doc", "Likes tea", "hash"));
      assertThat(client.deleteDocument("bank", "doc")).isTrue();
      assertThat(mapper.readTree(bodies.get(1)).path("operation_id").asText()).isEqualTo(operation);
      assertThat(mapper.readTree(bodies.get(1)).at("/items/0/document_id").asText())
          .isEqualTo("doc");
      assertThat(mapper.readTree(bodies.get(1)).path("async").asBoolean()).isTrue();
      assertThat(mapper.readTree(bodies.get(3)).path("types").toString())
          .isEqualTo("[\"world\",\"experience\"]");
      assertThat(methods)
          .contains(
              "DELETE /v1/default/banks/bank/documents/doc",
              "GET /v1/default/banks/bank/documents/doc");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void optionalByDefaultAndSupportsUnauthenticatedPrivateDeployments() {
    var disabled = new HindsightClient(false, "http://127.0.0.1:1", "", Duration.ofMillis(5), 4096);
    assertThat(disabled.isConfigured()).isFalse();
    assertThat(disabled.recall("bank", "tea")).isEmpty();
    assertThat(new HindsightClient(true, "", "", Duration.ofMillis(5), 4096).isConfigured())
        .isFalse();
    assertThat(
            new HindsightClient(true, "http://localhost:8888", "", Duration.ofMillis(5), 4096)
                .isConfigured())
        .isTrue();
  }
}

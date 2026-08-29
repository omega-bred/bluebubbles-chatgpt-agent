package io.breland.bbagent.server.agent.tools.wallart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WallartMcpClientTest {

  @Test
  void initializesDiscoversAndCallsWallartToolContract() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    AtomicReference<String> submittedPrompt = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/mcp", exchange -> handleMcp(exchange, mapper, submittedPrompt));
    server.start();
    try {
      WallartMcpProperties properties = new WallartMcpProperties();
      properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
      properties.setRequestTimeout(Duration.ofSeconds(5));
      WallartMcpClient client = new WallartMcpClient(properties, mapper);

      String output = client.showNewArt("colorful geometric trees");

      assertEquals("colorful geometric trees", submittedPrompt.get());
      JsonNode result = mapper.readTree(output);
      assertEquals("submitted", result.path("status").asText());
      assertTrue(result.path("content").toString().contains("workflowId=test-workflow"));
    } finally {
      server.stop(0);
    }
  }

  private static void handleMcp(
      HttpExchange exchange, ObjectMapper mapper, AtomicReference<String> submittedPrompt)
      throws IOException {
    if ("DELETE".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
      return;
    }
    if ("GET".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    JsonNode request = mapper.readTree(exchange.getRequestBody());
    String method = request.path("method").asText();
    switch (method) {
      case "initialize" ->
          respond(
              exchange,
              200,
              "test-session",
              "{\"jsonrpc\":\"2.0\",\"id\":"
                  + request.path("id")
                  + ",\"result\":{\"protocolVersion\":\""
                  + request.path("params").path("protocolVersion").asText()
                  + "\","
                  + "\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"wallart-mcp\","
                  + "\"version\":\"1.0.0\"}}}");
      case "notifications/initialized" -> respond(exchange, 202, null, null);
      case "tools/list" ->
          respond(
              exchange,
              200,
              null,
              "{\"jsonrpc\":\"2.0\",\"id\":"
                  + request.path("id")
                  + ",\"result\":{\"tools\":[{\"name\":\"showNewArt\","
                  + "\"description\":\"Show new art\",\"inputSchema\":{\"type\":\"object\","
                  + "\"properties\":{\"prompt\":{\"type\":\"string\"}},"
                  + "\"required\":[\"prompt\"],\"additionalProperties\":false}}]}}");
      case "tools/call" -> {
        submittedPrompt.set(request.path("params").path("arguments").path("prompt").asText());
        respond(
            exchange,
            200,
            null,
            "{\"jsonrpc\":\"2.0\",\"id\":"
                + request.path("id")
                + ",\"result\":{\"content\":[{\"type\":\"text\","
                + "\"text\":\"status=starting\"},{\"type\":\"text\","
                + "\"text\":\"workflowId=test-workflow\"}],\"isError\":false}}");
      }
      default -> throw new IllegalArgumentException("Unexpected MCP method " + method);
    }
  }

  private static void respond(HttpExchange exchange, int status, String sessionId, String body)
      throws IOException {
    if (sessionId != null) {
      exchange.getResponseHeaders().add("Mcp-Session-Id", sessionId);
    }
    if (body == null) {
      exchange.sendResponseHeaders(status, -1);
    } else {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }
}

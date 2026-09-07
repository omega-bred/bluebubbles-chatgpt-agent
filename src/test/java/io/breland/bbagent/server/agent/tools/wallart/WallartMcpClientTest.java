package io.breland.bbagent.server.agent.tools.wallart;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WallartMcpClientTest {
  @Test
  void initializesDiscoversAndCallsAllWallartContracts() throws Exception {
    try (var fixture = new McpFixture()) {
      var client = fixture.client;
      JsonNode output = fixture.mapper.readTree(client.showNewArt("geometric trees"));
      assertEquals("submitted", output.path("status").asText());
      assertTrue(output.path("content").toString().contains("workflowId=test-workflow"));
      assertEquals("geometric trees", fixture.arguments().path("prompt").asText());
      client.showNewArt(null);
      assertEquals(0, fixture.arguments().size());

      var current = client.getCurrentArt();
      assertEquals("image/png", current.mimeType());
      assertEquals(fixture.imageData, current.data());

      var image =
          Map.<String, Object>of(
              "data", fixture.imageData, "mime_type", "image/png", "description", "base scene");
      client.showImage(image);
      assertEquals("showImage", fixture.lastCall.get().path("name").asText());
      assertEquals(fixture.mapper.valueToTree(image), fixture.arguments().get("image"));
      client.composeArt(
          "add a person",
          List.of(
              image,
              Map.of(
                  "data", fixture.imageData, "mime_type", "image/png", "description", "person")));
      assertEquals("composeArt", fixture.lastCall.get().path("name").asText());
      assertEquals("add a person", fixture.arguments().path("prompt").asText());
      assertEquals(
          "base scene", fixture.arguments().path("images").get(0).path("description").asText());
      assertEquals(
          "person", fixture.arguments().path("images").get(1).path("description").asText());

      var status = fixture.mapper.readTree(client.getArtStatus("test-workflow"));
      assertEquals("test-workflow", fixture.arguments().path("workflowId").asText());
      assertEquals("checked", status.path("status").asText());
      assertEquals("FAILED", status.path("structured_content").path("status").asText());
      assertFalse(status.toString().contains("submitted"));
    }
  }

  @Test
  void rejectsRemoteErrorsWithoutLeakingEchoedImageData() throws Exception {
    try (var fixture = new McpFixture()) {
      fixture.overrideResult =
          Map.of(
              "isError",
              true,
              "content",
              List.of(Map.of("type", "text", "text", "private image payload")));
      var error = assertThrows(IllegalStateException.class, () -> fixture.client.showNewArt("art"));
      assertFalse(error.getMessage().contains("private image payload"));
      assertThrows(IllegalStateException.class, fixture.client::getCurrentArt);
    }
  }

  @Test
  void rejectsUnavailableToolsAndMissingImageContent() throws Exception {
    try (var fixture = new McpFixture()) {
      fixture.advertisedTools = List.of("showNewArt");
      assertThrows(IllegalStateException.class, fixture.client::getCurrentArt);
      assertNull(fixture.lastCall.get());
      fixture.advertisedTools = List.of("getCurrentArt");
      fixture.overrideResult =
          Map.of(
              "content",
              List.of(Map.of("type", "text", "text", "no current art")),
              "isError",
              false);
      assertThrows(IllegalStateException.class, fixture.client::getCurrentArt);
    }
  }

  private static final class McpFixture implements AutoCloseable {
    final ObjectMapper mapper = new ObjectMapper();
    final HttpServer server;
    final WallartMcpClient client;
    final String imageData;
    final AtomicReference<JsonNode> lastCall = new AtomicReference<>();
    volatile List<String> advertisedTools =
        List.of("showNewArt", "showImage", "composeArt", "getCurrentArt", "getArtStatus");
    volatile Map<String, Object> overrideResult;

    McpFixture() throws Exception {
      imageData = WallartImageInputsTest.png();
      server =
          HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext("/mcp", this::handle);
      server.start();
      WallartMcpProperties properties = new WallartMcpProperties();
      properties.setBaseUrl(
          new java.net.URI(
                  "http",
                  null,
                  server.getAddress().getAddress().getHostAddress(),
                  server.getAddress().getPort(),
                  null,
                  null,
                  null)
              .toString());
      properties.setRequestTimeout(Duration.ofSeconds(5));
      client = new WallartMcpClient(properties, mapper);
    }

    JsonNode arguments() {
      return lastCall.get().path("arguments");
    }

    private void handle(HttpExchange exchange) throws IOException {
      if ("DELETE".equals(exchange.getRequestMethod())
          || "GET".equals(exchange.getRequestMethod())) {
        respond(exchange, "GET".equals(exchange.getRequestMethod()) ? 405 : 200, null);
        return;
      }
      JsonNode request = mapper.readTree(exchange.getRequestBody());
      if ("notifications/initialized".equals(request.path("method").asText())) {
        respond(exchange, 202, null);
        return;
      }
      Object result =
          switch (request.path("method").asText()) {
            case "initialize" -> {
              exchange.getResponseHeaders().add("Mcp-Session-Id", "test-session");
              yield Map.of(
                  "protocolVersion",
                  request.path("params").path("protocolVersion").asText(),
                  "capabilities",
                  Map.of("tools", Map.of()),
                  "serverInfo",
                  Map.of("name", "wallart-mcp", "version", "1.0.0"));
            }
            case "tools/list" ->
                Map.of(
                    "tools",
                    advertisedTools.stream()
                        .map(
                            name ->
                                Map.of(
                                    "name",
                                    name,
                                    "description",
                                    name,
                                    "inputSchema",
                                    Map.of("type", "object", "properties", Map.of())))
                        .toList());
            case "tools/call" -> {
              lastCall.set(request.path("params"));
              if (overrideResult != null) yield overrideResult;
              String name = request.path("params").path("name").asText();
              if ("getCurrentArt".equals(name)) {
                yield Map.of(
                    "content",
                    List.of(Map.of("type", "image", "mimeType", "image/png", "data", imageData)),
                    "isError",
                    false);
              }
              if ("getArtStatus".equals(name)) {
                yield Map.of(
                    "content",
                    List.of(Map.of("type", "text", "text", "FAILED")),
                    "structuredContent",
                    Map.of("status", "FAILED", "workflowId", "test-workflow"),
                    "isError",
                    false);
              }
              yield Map.of(
                  "content",
                  List.of(
                      Map.of("type", "text", "text", "status=starting"),
                      Map.of("type", "text", "text", "workflowId=test-workflow")),
                  "isError",
                  false);
            }
            default -> throw new IllegalArgumentException("Unexpected MCP method");
          };
      respond(
          exchange,
          200,
          mapper.writeValueAsString(
              Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result)));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
      if (body == null) exchange.sendResponseHeaders(status, -1);
      else {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
      exchange.close();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}

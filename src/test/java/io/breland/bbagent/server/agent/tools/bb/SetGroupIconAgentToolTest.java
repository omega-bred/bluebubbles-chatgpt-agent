package io.breland.bbagent.server.agent.tools.bb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SetGroupIconAgentToolTest {
  @Test
  void generatesPngWithoutLegacyResponseFormatAndUploadsThenDeletesIt() throws Exception {
    var json = new ObjectMapper();
    var request = new AtomicReference<JsonNode>();
    byte[] generated = "synthetic-icon-bytes".getBytes(StandardCharsets.UTF_8);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/images/generations",
        exchange -> {
          request.set(json.readTree(exchange.getRequestBody()));
          byte[] response =
              json.writeValueAsBytes(
                  Map.of(
                      "created",
                      1,
                      "data",
                      List.of(Map.of("b64_json", Base64.getEncoder().encodeToString(generated)))));
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    var client =
        OpenAIOkHttpClient.builder()
            .apiKey("test-only")
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
            .maxRetries(0)
            .build();
    try {
      var bb = mock(BBHttpClientWrapper.class);
      var uploaded = new AtomicReference<Path>();
      when(bb.setConversationIcon(eq("synthetic-group"), any(Path.class)))
          .thenAnswer(
              invocation -> {
                Path path = invocation.getArgument(1);
                uploaded.set(path);
                assertArrayEquals(generated, Files.readAllBytes(path));
                return true;
              });
      var message =
          new IncomingMessage(
              "synthetic-group",
              "synthetic-message",
              null,
              "draw an icon",
              false,
              "iMessage",
              "synthetic-sender",
              true,
              Instant.EPOCH,
              List.of(),
              false);
      var context = new ToolContext(null, null, json, null, message, null);
      var tool = new SetGroupIconAgentTool(bb, () -> client).getTool();

      assertEquals(
          "updated", tool.handler().apply(context, json.readTree("{\"prompt\":\"a fox\"}")));
      var wire = request.get();
      assertNotNull(wire);
      assertEquals("gpt-image-2.6-sunburst", wire.path("model").asText());
      assertEquals("1024x1024", wire.path("size").asText());
      assertEquals("png", wire.path("output_format").asText());
      assertEquals("a fox", wire.path("prompt").asText());
      assertFalse(wire.has("response_format"));
      verify(bb).setConversationIcon(eq("synthetic-group"), any(Path.class));
      assertNotNull(uploaded.get());
      assertFalse(Files.exists(uploaded.get()));
    } finally {
      client.close();
      server.stop(0);
    }
  }
}

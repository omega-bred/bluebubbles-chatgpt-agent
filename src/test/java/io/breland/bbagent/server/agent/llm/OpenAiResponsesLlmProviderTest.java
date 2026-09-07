package io.breland.bbagent.server.agent.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.FunctionTool;
import com.sun.net.httpserver.HttpServer;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.tools.AgentTool;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiResponsesLlmProviderTest {
  @Test
  void nativeImageGenerationCoexistsWithDiscoveredToolsOnTheWire() throws Exception {
    ObjectMapper json = new ObjectMapper();
    AtomicReference<JsonNode> received = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/responses",
        exchange -> {
          received.set(json.readTree(exchange.getRequestBody()));
          byte[] body = "{\"output\":[]}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    var client =
        OpenAIOkHttpClient.builder()
            .apiKey("test-key")
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .build();
    try {
      var clients = mock(OpenAiClientProvider.class);
      when(clients.get()).thenReturn(client);
      var provider =
          new OpenAiResponsesLlmProvider(clients, new ModelPicker(mock(ModelAccessService.class)));
      var message =
          new IncomingMessage(
              "chat-1",
              "message-1",
              null,
              "draw an image",
              false,
              "iMessage",
              "Alice",
              false,
              Instant.parse("2026-09-07T12:00:00Z"),
              List.of(),
              false);
      var chatgpt =
          new ModelAccessService.ModelAccess(
              "account-1",
              true,
              "chatgpt",
              "ChatGPT",
              ModelAccessService.PREMIUM_RESPONSES_MODEL,
              true,
              List.of());
      var search = tool("toolSearchTool");
      var wall = tool("showNewArt");
      for (List<AgentTool> functions : List.of(List.of(search), List.of(search, wall))) {
        provider.createResponse(new LlmRequest(chatgpt, List.of(), functions, message, null));
        JsonNode request = received.get();
        assertEquals("openai/gpt-5.6-terra", request.path("model").asText());
        assertEquals(1, countTools(request, "image_generation"));
        assertEquals(functions.size(), countTools(request, "function"));
        for (JsonNode tool : request.path("tools")) {
          if ("image_generation".equals(tool.path("type").asText())) {
            assertEquals("gpt-image-1.5", tool.path("model").asText());
          }
        }
      }
      var free =
          new ModelAccessService.ModelAccess(
              "account-2",
              false,
              "local",
              "Free",
              ModelAccessService.STANDARD_RESPONSES_MODEL,
              false,
              List.of());
      provider.createResponse(new LlmRequest(free, List.of(), List.of(search), message, null));
      assertEquals(0, countTools(received.get(), "image_generation"));
      assertEquals(1, countTools(received.get(), "function"));
      var lxmf =
          new IncomingMessage(
              IncomingMessage.TRANSPORT_LXMF,
              "lxmf:test-chat",
              "lxmf-message",
              null,
              "draw an image",
              false,
              "LXMF",
              "test-sender",
              false,
              message.timestamp(),
              List.of(),
              false);
      provider.createResponse(new LlmRequest(chatgpt, List.of(), List.of(search), lxmf, null));
      assertEquals("openai/gpt-5.6-terra", received.get().path("model").asText());
      assertEquals(0, countTools(received.get(), "image_generation"));
      assertEquals(1, countTools(received.get(), "function"));
    } finally {
      client.close();
      server.stop(0);
    }
  }

  private static long countTools(JsonNode request, String type) {
    long count = 0;
    for (JsonNode tool : request.path("tools")) {
      if (type.equals(tool.path("type").asText())) count++;
    }
    return count;
  }

  private static AgentTool tool(String name) {
    return new AgentTool(
        name, "Test function", FunctionTool.Parameters.builder().build(), false, null);
  }
}

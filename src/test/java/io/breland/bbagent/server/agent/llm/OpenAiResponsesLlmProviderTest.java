package io.breland.bbagent.server.agent.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseInputItem;
import com.sun.net.httpserver.HttpServer;
import io.breland.bbagent.server.agent.AgentResponseHelper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.tools.AgentTool;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiResponsesLlmProviderTest {
  @Test
  void standardLunaKeepsImageInputReasoningAndFunctionResultsOnResponsesWire() throws Exception {
    var json = ObjectMappers.jsonMapper();
    var requests = new CopyOnWriteArrayList<JsonNode>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/responses",
        exchange -> {
          requests.add(json.readTree(exchange.getRequestBody()));
          String response =
              requests.size() == 1
                  ? """
                    {"id":"resp-fixture-call","model":"gpt-6-luna","output":[
                      {"type":"function_call","id":"fc-fixture","status":"completed",
                       "call_id":"fixture-call","name":"fixture_record_color",
                       "arguments":"{\\"color\\":\\"red\\"}"}]}
                    """
                  : """
                    {"id":"resp-fixture-result","model":"gpt-6-luna","output":[
                      {"type":"message","id":"msg-fixture","role":"assistant","status":"completed",
                       "content":[{"type":"output_text","text":"LUNA_VISION_TOOL_OK","annotations":[]}]}]}
                    """;
          byte[] body = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
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
      var modelAccess = new ModelAccessService(null, null);
      var access = modelAccess.resolve((String) null);
      var provider = new OpenAiResponsesLlmProvider(clients, new ModelPicker(modelAccess));
      var message =
          new IncomingMessage(
              "synthetic-chat",
              "synthetic-message",
              null,
              "Inspect the synthetic image",
              false,
              "iMessage",
              "Synthetic Fixture",
              false,
              Instant.parse("2026-10-23T00:00:00Z"),
              List.of(),
              false);
      var input = new ArrayList<ResponseInputItem>();
      input.add(
          json.readValue(
              "{\"role\":\"developer\",\"content\":\"Use the color fixture tool, then complete.\"}",
              ResponseInputItem.class));
      input.add(
          json.readValue(
              """
              {"role":"user","content":[
                {"type":"input_text","text":"Record the synthetic image color."},
                {"type":"input_image","detail":"auto","image_url":"data:image/png;base64,fixture"}]}
              """,
              ResponseInputItem.class));
      var function =
          json.readValue(
              """
              {"type":"function","name":"fixture_record_color","parameters":{
                "type":"object","properties":{"color":{"type":"string"}},"required":["color"]}}
              """,
              FunctionTool.class);
      var tool =
          new AgentTool(
              function.name(),
              "No-op synthetic fixture",
              function.parameters().orElseThrow(),
              false,
              null);
      var first =
          provider.createResponse(new LlmRequest(access, input, List.of(tool), message, null));
      var calls = AgentResponseHelper.extractFunctionCalls(first);
      assertEquals(1, calls.size());
      assertEquals("fixture_record_color", calls.getFirst().name());
      input.addAll(AgentResponseHelper.extractToolContextItems(first, calls));
      input.add(
          ResponseInputItem.ofFunctionCallOutput(
              ResponseInputItem.FunctionCallOutput.builder()
                  .callId(calls.getFirst().callId())
                  .output("recorded red")
                  .build()));
      var last =
          provider.createResponse(new LlmRequest(access, input, List.of(tool), message, null));
      assertTrue(AgentResponseHelper.extractResponseText(last).contains("LUNA_VISION_TOOL_OK"));
      assertFalse(access.premium());
      assertEquals("local", access.currentModelKey());
      assertEquals(2, requests.size());
      for (JsonNode request : requests) {
        assertEquals("openai/gpt-6-luna", request.path("model").asText());
        assertEquals("high", request.path("reasoning").path("effort").asText());
        assertEquals(1500, request.path("max_output_tokens").asInt());
        assertEquals(1, countTools(request, "function"));
        assertEquals(0, countTools(request, "image_generation"));
        assertEquals("developer", request.path("input").get(0).path("role").asText());
        assertEquals(
            "input_image",
            request.path("input").get(1).path("content").get(1).path("type").asText());
      }
      var result = requests.getLast().path("input").get(3);
      assertEquals("function_call_output", result.path("type").asText());
      assertEquals("fixture-call", result.path("call_id").asText());
      assertEquals("recorded red", result.path("output").asText());
    } finally {
      client.close();
      server.stop(0);
    }
  }

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

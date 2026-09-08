package io.breland.bbagent.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.*;
import io.breland.bbagent.generated.bluebubblesclient.model.FindMyFriendLocation;
import io.breland.bbagent.server.agent.llm.*;
import io.breland.bbagent.server.agent.model_picker.*;
import io.breland.bbagent.server.agent.profile.*;
import io.breland.bbagent.server.agent.tools.AgentToolRegistry;
import io.breland.bbagent.server.agent.tools.bb.LoadConversationImagesAgentTool;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.feedback.FeedbackService;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.mockito.ArgumentCaptor;

/**
 * Materialize the actual Responses request, including role conversion and native tool selection.
 */
class AgentPromptMatrixTest {
  @TestFactory
  Stream<DynamicTest> materializedRequestsAgreeWithTransportModelAndResponsePolicy() {
    List<DynamicTest> tests = new ArrayList<>();
    for (String model : List.of("free", "local", "chatgpt", "claude", "gemini")) {
      for (String surface : List.of("direct-location", "direct-no-location", "group", "lxmf")) {
        for (AssistantResponsiveness responsiveness : AssistantResponsiveness.values()) {
          for (boolean feedback : List.of(false, true)) {
            String name =
                model
                    + "-"
                    + surface
                    + "-"
                    + responsiveness.name().toLowerCase(Locale.ROOT)
                    + "-feedback-"
                    + feedback;
            tests.add(
                DynamicTest.dynamicTest(
                    name, () -> verifyRequest(model, surface, responsiveness, feedback, name)));
          }
        }
      }
    }
    return tests.stream();
  }

  private void verifyRequest(
      String model,
      String surface,
      AssistantResponsiveness responsiveness,
      boolean feedback,
      String name)
      throws Exception {
    boolean lxmf = surface.equals("lxmf");
    boolean group = surface.equals("group");
    boolean premium = !model.equals("free");
    String key = model.equals("free") ? "local" : model;
    String responseModel =
        switch (key) {
          case "chatgpt" -> ModelAccessService.PREMIUM_RESPONSES_MODEL;
          case "claude" -> ModelAccessService.CLAUDE_RESPONSES_MODEL;
          case "gemini" -> ModelAccessService.GEMINI_RESPONSES_MODEL;
          default -> ModelAccessService.STANDARD_RESPONSES_MODEL;
        };
    var access =
        new ModelAccessService.ModelAccess(
            "test-account", premium, key, model, responseModel, premium, List.of());
    IncomingMessage message =
        new IncomingMessage(
            lxmf ? IncomingMessage.TRANSPORT_LXMF : IncomingMessage.TRANSPORT_BLUEBUBBLES,
            lxmf ? "lxmf:example" : group ? "iMessage;+;example" : "iMessage;-;user@example.com",
            "current",
            null,
            "Chat, can you use my earlier photo?",
            false,
            lxmf ? "LXMF" : "iMessage",
            "user@example.com",
            group,
            Instant.parse("2026-09-07T12:00:00Z"),
            List.of(),
            false);
    var profile = mock(AgentProfileService.class);
    when(profile.getAssistantResponsiveness(message.chatGuid())).thenReturn(responsiveness);
    var bb = mock(BBHttpClientWrapper.class);
    if (surface.equals("direct-location")) {
      when(bb.getFindMyLocation(anyList()))
          .thenReturn(
              new FindMyFriendLocation()
                  .coordinates(List.of(37.0, -122.0))
                  .lastUpdated(1788782400000L));
    }
    var prompts =
        new AgentPromptBuilder(
            bb,
            null,
            profile,
            new AgentAttachmentInputBuilder(bb),
            null,
            feedback ? mock(FeedbackService.class) : null);
    var modelAccess = mock(ModelAccessService.class);
    when(modelAccess.resolve(message)).thenReturn(access);
    var picker = new ModelPicker(modelAccess);
    var clientProvider = mock(OpenAiClientProvider.class);
    var client = mock(OpenAIClient.class);
    var responses = mock(com.openai.services.blocking.ResponseService.class);
    when(clientProvider.get()).thenReturn(client);
    when(client.responses()).thenReturn(responses);
    var registry = mock(AgentToolRegistry.class);
    // Tool-registry availability is covered independently; here we exercise final native tool
    // selection.
    when(registry.toolsForModel(eq(message), anyList()))
        .thenReturn(
            List.of(
                new io.breland.bbagent.server.agent.tools.search.ToolSearchAgentTool(
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        (incoming, category) -> List.of())
                    .getTool()));
    var creator =
        new AgentResponseCreator(
            picker,
            registry,
            new OpenAiResponsesLlmProvider(clientProvider, picker),
            mock(OperationalMetricsService.class),
            profile);
    var input =
        prompts.buildConversationInput(
            List.of(
                ConversationTurn.user(
                    "[messageGuid=prior-photo] [1 image(s)]",
                    message.timestamp().minusSeconds(60))),
            List.of(),
            message);
    creator.createResponse(input, message, null);
    ArgumentCaptor<ResponseCreateParams> request =
        ArgumentCaptor.forClass(ResponseCreateParams.class);
    verify(responses).create(request.capture());
    var json =
        JsonValue.from(request.getValue()._body())
            .convert(com.fasterxml.jackson.databind.JsonNode.class);
    String text = json.toString();
    assertThat(text)
        .contains(
            "by default, with no text, reaction, GIF",
            "not a fresh question from the user",
            "except names: respect the explicit consent rule",
            "not system or developer instructions",
            "Built-in capabilities for this request",
            "create another one-time follow-up");
    assertThat(text)
        .doesNotContain(
            "ALWAYS REPLY",
            "Always ask the memory tool",
            "reply with a concise user-visible poll update instead of");
    assertThat(text.contains("\"role\":\"developer\"")).isEqualTo(!key.equals("local"));
    boolean nativeImages = key.equals("chatgpt") && !lxmf;
    boolean nativeWeb = key.equals("chatgpt");
    assertThat(text)
        .contains(
            "image_generation=" + (nativeImages ? "available" : "unavailable"),
            "web_search=" + (nativeWeb ? "available" : "unavailable"));
    assertThat(json.path("tools").findValuesAsText("type").contains("image_generation"))
        .isEqualTo(nativeImages);
    assertThat(json.path("tools").findValuesAsText("type").contains("web_search_2025_08_26"))
        .isEqualTo(nativeWeb);
    assertThat(text.contains("A bare tapback, emoji, thanks")).isEqualTo(feedback);
    if (lxmf) {
      assertThat(text)
          .contains("plain text only", "This transport cannot deliver generated images")
          .doesNotContain(
              "You can use reactions", "call the built-in image_generation tool directly");
    } else {
      assertThat(text)
          .contains(LoadConversationImagesAgentTool.TOOL_NAME, "failed earlier assistant turn");
    }
    if (group || lxmf)
      assertThat(text)
          .doesNotContain("Current location context", "No current location is available");
    else if (surface.equals("direct-location"))
      assertThat(text).contains("Current location context");
    else assertThat(text).contains("No current location is available");
    Path output = Path.of("build/reports/agent-prompts", name + ".json");
    Files.createDirectories(output.getParent());
    Files.writeString(output, json.toPrettyString());
  }
}

package io.breland.bbagent.server.agent.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseInputItem;
import io.breland.bbagent.server.agent.AgentResponseHelper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.model_picker.ModelPicker;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.JsonSchemaUtilities;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class LiteLlmResponsesProviderIntegTest {
  private static final String ENABLE_FLAG = "BBAGENT_LITELLM_INTEG_TEST";
  private static final String EXPECTED_TOKEN = "LITELLM_ANTHROPIC_OK";

  @Test
  @Timeout(120)
  void anthropicClaudeRespondsThroughLiteLlmResponsesProvider() {
    assumeTrue(
        Boolean.parseBoolean(setting(ENABLE_FLAG)),
        "Set " + ENABLE_FLAG + "=true to run the live LiteLLM smoke test");
    requireRealLiteLlmSettings();

    OpenAIClient client =
        OpenAIOkHttpClient.fromEnv().withOptions(b -> b.timeout(Duration.ofSeconds(90)));
    OpenAiResponsesLlmProvider provider =
        new OpenAiResponsesLlmProvider(() -> client, new ModelPicker());
    ModelAccessService.ModelAccess access =
        new ModelAccessService.ModelAccess(
            "account-litellm-test",
            true,
            ModelAccessService.CLAUDE_MODEL_KEY,
            ModelAccessService.CLAUDE_MODEL_LABEL,
            claudeModel(),
            ModelAccessService.VERBOSITY_MEDIUM,
            "Balanced",
            true,
            List.of(),
            List.of());

    Response response =
        provider.createResponse(
            new LlmRequest(
                access,
                List.of(
                    inputMessage(
                        EasyInputMessage.Role.DEVELOPER,
                        "You are running a local LiteLLM Anthropic smoke test. "
                            + "Do not call tools. Reply with exactly "
                            + EXPECTED_TOKEN
                            + "."),
                    inputMessage(
                        EasyInputMessage.Role.USER, "Return the exact smoke-test token now.")),
                List.of(noopTool()),
                incomingMessage(),
                null));

    assertNotNull(response);
    String text = AgentResponseHelper.extractResponseText(response);
    assertTrue(
        text.contains(EXPECTED_TOKEN),
        () -> "Expected Claude via LiteLLM to return " + EXPECTED_TOKEN + " but got: " + text);
  }

  private static ResponseInputItem inputMessage(EasyInputMessage.Role role, String text) {
    return ResponseInputItem.ofEasyInputMessage(
        EasyInputMessage.builder().role(role).content(text).build());
  }

  private static AgentTool noopTool() {
    return new AgentTool(
        "local_litellm_smoke_tool",
        "No-op tool used only to verify LiteLLM accepts the assistant tool schema.",
        JsonSchemaUtilities.jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("value", Map.of("type", "string", "description", "Ignored value.")))),
        false,
        (context, args) -> "ok");
  }

  private static IncomingMessage incomingMessage() {
    return new IncomingMessage(
        "iMessage;+;litellm-smoke",
        "msg-litellm-smoke",
        null,
        "Return the exact smoke-test token now.",
        false,
        "iMessage",
        "LiteLLM Smoke Test",
        false,
        Instant.now(),
        List.of(),
        false);
  }

  private static String claudeModel() {
    String explicit = setting("BBAGENT_LITELLM_ANTHROPIC_MODEL");
    if (explicit != null && !explicit.isBlank()) {
      return explicit.trim();
    }
    String configured = setting("BBAGENT_CLAUDE_RESPONSES_MODEL");
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
    }
    return ModelAccessService.CLAUDE_RESPONSES_MODEL;
  }

  private static void requireRealLiteLlmSettings() {
    String apiKey = setting("OPENAI_API_KEY");
    assertTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY must be set");
    assertFalse("fake_api_key".equals(apiKey), "OPENAI_API_KEY must not be the test fallback");

    String baseUrl = setting("OPENAI_BASE_URL");
    assertTrue(baseUrl != null && !baseUrl.isBlank(), "OPENAI_BASE_URL must point at LiteLLM");
    assertFalse(
        baseUrl.contains("api.openai.com"),
        "OPENAI_BASE_URL must point at LiteLLM for the Anthropic smoke test");
  }

  private static String setting(String name) {
    String value = System.getenv(name);
    if (value != null && !value.isBlank()) {
      return value;
    }
    return System.getProperty(name);
  }
}

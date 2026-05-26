package io.breland.bbagent.server.agent.tools.model;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.tools.ToolContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SetPreferredModelAgentToolTest {
  private final ModelAccessService modelAccessService = mock(ModelAccessService.class);
  private final SetPreferredModelAgentTool tool =
      new SetPreferredModelAgentTool(modelAccessService);
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void switchesPremiumModel() throws Exception {
    IncomingMessage message = message();
    ModelAccessService.ModelAccess access =
        new ModelAccessService.ModelAccess(
            "account-1",
            true,
            ModelAccessService.GEMINI_MODEL_KEY,
            ModelAccessService.GEMINI_MODEL_LABEL,
            ModelAccessService.GEMINI_RESPONSES_MODEL,
            true,
            List.of());
    when(modelAccessService.selectModel(message, "gemini"))
        .thenReturn(
            new ModelAccessService.ModelSelectionResult(
                true, access, "Model changed to Gemini for this account."));

    String output =
        tool.getTool().handler().apply(context(message), mapper.readTree("{\"model\":\"gemini\"}"));

    assertTrue(output.contains("\"current_model\":\"gemini\""));
    assertTrue(output.contains("\"user_facing_text\""));
  }

  @Test
  void reportsPremiumRequirement() throws Exception {
    IncomingMessage message = message();
    when(modelAccessService.selectModel(message, "claude"))
        .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "premium required"));

    String output =
        tool.getTool().handler().apply(context(message), mapper.readTree("{\"model\":\"claude\"}"));

    assertTrue(output.contains("premium required"));
  }

  private ToolContext context(IncomingMessage message) {
    ToolContext context = mock(ToolContext.class);
    when(context.message()).thenReturn(message);
    when(context.getMapper()).thenReturn(mapper);
    return context;
  }

  private IncomingMessage message() {
    return new IncomingMessage(
        "iMessage;+;chat-1",
        "msg-1",
        null,
        "use gemini",
        false,
        "iMessage",
        "Alice",
        false,
        Instant.now(),
        List.of(),
        false);
  }
}

package io.breland.bbagent.server.agent.tools.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.tools.ToolContext;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AssistantResponsivenessAgentToolTest {

  @Test
  void formatsResponseIndependentOfDefaultLocale() throws Exception {
    Locale originalLocale = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      ObjectMapper mapper = new ObjectMapper();
      ToolContext context = mock(ToolContext.class);
      when(context.getMapper()).thenReturn(mapper);

      String response =
          new AssistantResponsivenessAgentTool()
              .getTool()
              .handler()
              .apply(context, mapper.readTree("{\"responsiveness\":\"silent\"}"));

      assertEquals("updated to silent", response);
    } finally {
      Locale.setDefault(originalLocale);
    }
  }
}

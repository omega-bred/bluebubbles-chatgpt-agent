package io.breland.bbagent.server.agent.cadence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.gson.reflect.TypeToken;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import io.breland.bbagent.server.agent.ConversationTurn;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CadenceConversationHistoryTest {
  private final DataConverter converter =
      new CadenceWorkflowConfig()
          .cadenceWorkflowClient(mock(WorkflowServiceTChannel.class), new AgentWorkflowProperties())
          .getOptions()
          .getDataConverter();

  @Test
  void historyMetadataSurvivesCadenceActivitySerialization() {
    Instant timestamp = Instant.parse("2026-09-07T12:00:00Z");
    List<ConversationTurn> history =
        List.of(
            ConversationTurn.user("Alice: hello", timestamp),
            ConversationTurn.assistant(
                "Here is your photo.",
                timestamp,
                "[messageGuid=photo-message] [1 image(s)] [attachmentGuid=photo-attachment]"),
            ConversationTurn.assistant("A newly recorded reply", timestamp));

    assertThat(readHistory(converter.toData(history))).containsExactlyElementsOf(history);
  }

  @Test
  void historyWithoutMetadataRemainsReadableForExistingWorkflows() {
    byte[] payload =
        """
        [{"role":"assistant","content":"Earlier reply","timestamp":"2026-09-07T12:00:00Z"}]
        """
            .getBytes(StandardCharsets.UTF_8);

    assertThat(readHistory(payload))
        .containsExactly(
            ConversationTurn.assistant("Earlier reply", Instant.parse("2026-09-07T12:00:00Z")));
  }

  private List<ConversationTurn> readHistory(byte[] payload) {
    return converter.fromData(
        payload, List.class, new TypeToken<List<ConversationTurn>>() {}.getType());
  }
}

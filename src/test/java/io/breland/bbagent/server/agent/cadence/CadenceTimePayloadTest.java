package io.breland.bbagent.server.agent.cadence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.serviceclient.WorkflowServiceGrpc;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CadenceTimePayloadTest {
  private static final String ISO_PAYLOAD = "[\"1970-01-01T00:00:01.500Z\",\"PT-1.5S\"]";

  private final DataConverter converter =
      new CadenceWorkflowConfig()
          .cadenceWorkflowClient(mock(WorkflowServiceGrpc.class), new AgentWorkflowProperties())
          .getOptions()
          .getDataConverter();

  @ParameterizedTest
  @ValueSource(strings = {ISO_PAYLOAD, "[1500,-1500]", "[\"1500\",\"-1500\"]"})
  void workflowTimeArgumentsReadLegacyMillisAndWriteIsoStrings(String payload) {
    Object[] arguments =
        converter.fromDataArray(payload.getBytes(UTF_8), Instant.class, Duration.class);

    assertThat(arguments).containsExactly(Instant.ofEpochMilli(1500), Duration.ofMillis(-1500));
    assertThat(converter.toData(arguments)).isEqualTo(ISO_PAYLOAD.getBytes(UTF_8));
  }
}

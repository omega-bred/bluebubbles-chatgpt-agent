package io.breland.bbagent.server.agent.cadence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.uber.cadence.*;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.serviceclient.IWorkflowService;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class CadenceWorkflowLauncherTest {
  @Test
  void listsGrpcEntitiesWithOptionalTimesAndByteArrayMemos() throws Exception {
    var service = mock(IWorkflowService.class);
    var client = mock(WorkflowClient.class);
    var converter = JsonDataConverter.getInstance();
    when(client.getService()).thenReturn(service);
    when(client.getOptions())
        .thenReturn(WorkflowClientOptions.newBuilder().setDataConverter(converter).build());
    var fields = new LinkedHashMap<String, byte[]>();
    fields.put("description", converter.toData("daily reminder"));
    fields.put("missing", null);
    fields.put("invalid", new byte[] {'{'});
    var running =
        new WorkflowExecutionInfo()
            .setExecution(new WorkflowExecution().setWorkflowId("scheduled-1").setRunId("run-1"))
            .setMemo(new Memo().setFields(fields));
    var completed =
        new WorkflowExecutionInfo()
            .setExecution(new WorkflowExecution().setWorkflowId("scheduled-2"))
            .setCloseStatus(WorkflowExecutionCloseStatus.COMPLETED)
            .setStartTime(123L)
            .setExecutionTime(456L);
    when(service.ListWorkflowExecutions(any()))
        .thenReturn(
            new ListWorkflowExecutionsResponse().setExecutions(List.of(running, completed)));
    var summaries =
        new CadenceWorkflowLauncher(client, new AgentWorkflowProperties())
            .listScheduledWorkflows("scheduled-");
    assertThat(summaries).hasSize(2);
    assertThat(summaries.getFirst().status()).isEqualTo("Running");
    assertThat(summaries.getFirst().startTimeMillis()).isNull();
    assertThat(summaries.getFirst().executionTimeMillis()).isNull();
    assertThat(summaries.getFirst().memo())
        .containsEntry("description", "daily reminder")
        .containsEntry("invalid", "[unreadable]")
        .doesNotContainKey("missing");
    assertThat(summaries.get(1).status()).isEqualTo("COMPLETED");
    assertThat(summaries.get(1).startTimeMillis()).isEqualTo(123L);
    assertThat(summaries.get(1).executionTimeMillis()).isEqualTo(456L);
  }
}

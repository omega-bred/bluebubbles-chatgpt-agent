package io.breland.bbagent.server.agent.cadence;

import com.uber.cadence.ListWorkflowExecutionsRequest;
import com.uber.cadence.ListWorkflowExecutionsResponse;
import com.uber.cadence.Memo;
import com.uber.cadence.TerminateWorkflowExecutionRequest;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionInfo;
import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.converter.DataConverter;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(WorkflowClient.class)
@Slf4j
public class CadenceWorkflowLauncher {

  private final WorkflowClient workflowClient;
  private final AgentWorkflowProperties workflowProperties;

  public CadenceWorkflowLauncher(
      WorkflowClient workflowClient, AgentWorkflowProperties workflowProperties) {
    this.workflowClient = workflowClient;
    this.workflowProperties = workflowProperties;
  }

  public @Nullable WorkflowExecution startWorkflow(CadenceMessageWorkflowRequest request) {
    return startWorkflow(request, null, null, null);
  }

  public @Nullable WorkflowExecution startScheduledWorkflow(
      CadenceMessageWorkflowRequest request,
      @Nullable Duration delayStart,
      @Nullable String cronSchedule,
      @Nullable Map<String, Object> memo) {
    return startWorkflow(request, delayStart, cronSchedule, memo);
  }

  public List<ScheduledWorkflowSummary> listScheduledWorkflows(String workflowIdPrefix) {
    if (workflowIdPrefix == null || workflowIdPrefix.isBlank()) {
      return List.of();
    }
    String query =
        "WorkflowId STARTS_WITH \""
            + workflowIdPrefix.replace("\"", "")
            + "\" AND ExecutionStatus = \"Running\"";
    ListWorkflowExecutionsRequest request = new ListWorkflowExecutionsRequest();
    request.setDomain(workflowProperties.getCadenceDomain());
    request.setPageSize(100);
    request.setQuery(query);
    ListWorkflowExecutionsResponse response;
    try {
      response = workflowClient.getService().ListWorkflowExecutions(request);
    } catch (Exception e) {
      log.warn("Failed to list scheduled workflows", e);
      return List.of();
    }
    if (response == null || response.getExecutions() == null) {
      return List.of();
    }
    List<ScheduledWorkflowSummary> summaries = new ArrayList<>();
    for (WorkflowExecutionInfo info : response.getExecutions()) {
      if (info == null || info.getExecution() == null) {
        continue;
      }
      summaries.add(
          new ScheduledWorkflowSummary(
              info.getExecution().getWorkflowId(),
              info.getExecution().getRunId(),
              Optional.ofNullable(info.getType()).map(type -> type.getName()).orElse(null),
              info.isSetCloseStatus() ? info.getCloseStatus().name() : "Running",
              info.isSetStartTime() ? info.getStartTime() : null,
              info.isSetExecutionTime() ? info.getExecutionTime() : null,
              decodeMemo(info.getMemo())));
    }
    return summaries;
  }

  public boolean terminateWorkflow(String workflowId, @Nullable String runId, String reason) {
    if (workflowId == null || workflowId.isBlank()) {
      return false;
    }
    TerminateWorkflowExecutionRequest request = new TerminateWorkflowExecutionRequest();
    request.setDomain(workflowProperties.getCadenceDomain());
    WorkflowExecution execution = new WorkflowExecution();
    execution.setWorkflowId(workflowId);
    if (runId != null && !runId.isBlank()) {
      execution.setRunId(runId);
    }
    request.setWorkflowExecution(execution);
    if (reason != null && !reason.isBlank()) {
      request.setReason(reason);
    }
    try {
      workflowClient.getService().TerminateWorkflowExecution(request);
      return true;
    } catch (Exception e) {
      log.warn("Failed to terminate workflow {}", workflowId, e);
      return false;
    }
  }

  private @Nullable WorkflowExecution startWorkflow(
      CadenceMessageWorkflowRequest request,
      @Nullable Duration delayStart,
      @Nullable String cronSchedule,
      @Nullable Map<String, Object> memo) {
    if (request == null || request.workflowContext() == null) {
      log.warn("Dropping cadence start workflow [ request or workflowContext is null ]");
      return null;
    }
    WorkflowOptions.Builder optionsBuilder =
        new WorkflowOptions.Builder()
            .setTaskList(workflowProperties.getCadenceTaskList())
            .setWorkflowId(request.workflowContext().workflowId())
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.TerminateIfRunning)
            .setExecutionStartToCloseTimeout(Duration.ofMinutes(5))
            .setTaskStartToCloseTimeout(Duration.ofMinutes(1));
    if (delayStart != null && !delayStart.isNegative() && !delayStart.isZero()) {
      optionsBuilder.setDelayStart(delayStart);
    }
    if (cronSchedule != null && !cronSchedule.isBlank()) {
      optionsBuilder.setCronSchedule(cronSchedule);
    }
    if (memo != null && !memo.isEmpty()) {
      optionsBuilder.setMemo(memo);
    }
    WorkflowOptions options = optionsBuilder.build();
    CadenceMessageWorkflow workflow =
        workflowClient.newWorkflowStub(CadenceMessageWorkflow.class, options);
    log.info("Submitting workflow: {}", workflow);
    WorkflowExecution execution = WorkflowClient.start(workflow::run, request);
    log.info("Submitted workflow: {}, {}", workflow, execution);
    return execution;
  }

  private Map<String, Object> decodeMemo(@Nullable Memo memo) {
    if (memo == null || memo.getFields() == null || memo.getFields().isEmpty()) {
      return Collections.emptyMap();
    }
    DataConverter converter = workflowClient.getOptions().getDataConverter();
    Map<String, Object> decoded = new LinkedHashMap<>();
    for (Map.Entry<String, java.nio.ByteBuffer> entry : memo.getFields().entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        continue;
      }
      try {
        byte[] data = new byte[entry.getValue().remaining()];
        entry.getValue().duplicate().get(data);
        Object value = converter.fromData(data, Object.class, Object.class);
        decoded.put(entry.getKey(), value);
      } catch (Exception e) {
        decoded.put(entry.getKey(), "[unreadable]");
      }
    }
    return decoded;
  }

  public record ScheduledWorkflowSummary(
      String workflowId,
      String runId,
      String workflowType,
      String status,
      Long startTimeMillis,
      Long executionTimeMillis,
      Map<String, Object> memo) {}
}

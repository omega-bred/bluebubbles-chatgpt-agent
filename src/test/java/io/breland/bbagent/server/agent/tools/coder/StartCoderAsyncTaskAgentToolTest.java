package io.breland.bbagent.server.agent.tools.coder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uber.cadence.WorkflowExecution;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.cadence.models.CadenceMessageWorkflowRequest;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.workflowcallback.WorkflowCallbackService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class StartCoderAsyncTaskAgentToolTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void startsCoderTaskWithCallbackAndFallbackSchedule() throws Exception {
    CoderMcpClient coder = Mockito.mock(CoderMcpClient.class);
    WorkflowCallbackService callbacks = Mockito.mock(WorkflowCallbackService.class);
    CadenceWorkflowLauncher launcher = Mockito.mock(CadenceWorkflowLauncher.class);
    when(coder.isConfigured()).thenReturn(true);
    when(coder.isLinked("+18035551212")).thenReturn(true);
    when(coder.callMcpTool(
            eq("+18035551212"), eq(StartCoderAsyncTaskAgentTool.LIST_TEMPLATES_MCP_TOOL), anyMap()))
        .thenReturn(templateListResult());
    when(callbacks.createCallback(any(), anyString(), anyString(), any()))
        .thenReturn(
            new WorkflowCallbackService.CreatedCallback(
                "callback-1",
                "https://chatagent.example/callback",
                "whsec_secret",
                Instant.parse("2026-04-15T00:00:00Z"),
                "CALLBACK INSTRUCTIONS"));
    when(coder.callMcpTool(
            eq("+18035551212"), eq(StartCoderAsyncTaskAgentTool.CREATE_TASK_MCP_TOOL), anyMap()))
        .thenReturn(coderCreateTaskResult());
    WorkflowExecution scheduledExecution = new WorkflowExecution();
    scheduledExecution.setWorkflowId("scheduled:any;-;+18035551212:abc");
    scheduledExecution.setRunId("run-1");
    when(launcher.startScheduledWorkflow(
            any(CadenceMessageWorkflowRequest.class), any(), any(), anyMap()))
        .thenReturn(scheduledExecution);

    AgentTool tool = new StartCoderAsyncTaskAgentTool(coder, callbacks, launcher).getTool();
    ObjectNode request = mapper.createObjectNode();
    request.put(
        "task", "clone omega-bred/bluebubbles-chatgpt-agent and summarize the last 10 commits");

    String output = tool.handler().apply(context(), request);

    JsonNode result = mapper.readTree(output);
    assertTrue(result.path("started").asBoolean());
    assertEquals("callback-1", result.path("callback_id").asText());
    assertEquals(
        "6c085203-a06d-4295-b754-c82c8b3bd124", result.path("template_version_id").asText());
    assertEquals("scheduled", result.path("fallback_check").asText());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> createArgsCaptor =
        ArgumentCaptor.forClass((Class) Map.class);
    verify(coder)
        .callMcpTool(
            eq("+18035551212"),
            eq(StartCoderAsyncTaskAgentTool.CREATE_TASK_MCP_TOOL),
            createArgsCaptor.capture());
    Map<String, Object> createArgs = createArgsCaptor.getValue();
    assertEquals("6c085203-a06d-4295-b754-c82c8b3bd124", createArgs.get("template_version_id"));
    assertTrue(createArgs.get("input").toString().contains("CALLBACK INSTRUCTIONS"));
    verify(launcher)
        .startScheduledWorkflow(any(CadenceMessageWorkflowRequest.class), any(), any(), anyMap());
  }

  private ToolContext context() {
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    when(agent.getObjectMapper()).thenReturn(mapper);
    IncomingMessage message =
        new IncomingMessage(
            "any;-;+18035551212",
            "msg-1",
            null,
            "start coder task",
            false,
            BBMessageAgent.IMESSAGE_SERVICE,
            "+18035551212",
            false,
            Instant.now(),
            List.of(),
            false);
    return new ToolContext(
        agent,
        message,
        new AgentWorkflowContext(
            "any;-;+18035551212", "any;-;+18035551212", "msg-1", Instant.now()));
  }

  private String templateListResult() {
    return """
        {
          "is_error": false,
          "content": [
            {
              "type": "text",
              "text": "[{\\"display_name\\":\\"CPU Pod\\",\\"name\\":\\"cpu\\",\\"active_version_id\\":\\"f9b6bd13-880a-4518-95bd-4ee0907a1dee\\"},{\\"display_name\\":\\"Task via Opencode\\",\\"name\\":\\"tasks\\",\\"description\\":\\"AI Task via OpenCode\\",\\"active_version_id\\":\\"6c085203-a06d-4295-b754-c82c8b3bd124\\"}]"
            }
          ]
        }
        """;
  }

  private String coderCreateTaskResult() {
    return """
        {
          "is_error": false,
          "content": [
            {
              "type": "text",
              "text": "{\\"id\\":\\"task-1\\",\\"status\\":\\"pending\\"}"
            }
          ]
        }
        """;
  }
}

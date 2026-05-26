package io.breland.bbagent.server.agent.llm;

import com.openai.models.responses.ResponseInputItem;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.tools.AgentTool;
import java.util.List;

public record LlmRequest(
    ModelAccessService.ModelAccess modelAccess,
    List<ResponseInputItem> inputItems,
    List<AgentTool> tools,
    IncomingMessage message,
    AgentWorkflowContext workflowContext) {}

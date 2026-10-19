package io.breland.bbagent.server.agent.decisions;

import com.openai.models.responses.*;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentToolRegistry;
import io.breland.bbagent.server.agent.tools.bb.SendReactionAgentTool;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

/** Adapt a decision to the existing durable tool loop; never send outside that loop. */
public final class DecisionResponseRouter {
  private DecisionResponseRouter() {}

  public static @Nullable Response route(
      List<ResponseInputItem> input,
      IncomingMessage message,
      AgentWorkflowContext context,
      AgentToolRegistry tools) {
    if (context == null) return null;
    var routing = context.decisionRouting();
    if (routing.responseMode() == DecisionRouting.ResponseMode.NO_RESPONSE) return silence();
    if (routing.responseMode() != DecisionRouting.ResponseMode.REACTION
        || message == null
        || message.messageGuid() == null
        || message.messageGuid().isBlank()) return null;
    String callId =
        "decision_reaction_"
            + UUID.nameUUIDFromBytes(
                (context.workflowId() + ":" + context.messageGuid())
                    .getBytes(StandardCharsets.UTF_8));
    // Only our own structured tool output counts as confirmation, never quoted user text.
    for (ResponseInputItem item : input) {
      if (item.isFunctionCallOutput() && callId.equals(item.asFunctionCallOutput().callId())) {
        var output = item.asFunctionCallOutput().output();
        return output.isString() && "sent".equals(output.asString()) ? silence() : null;
      }
    }
    // A missing output or failed send must not trigger another synthetic reaction attempt.
    if (input.stream()
        .anyMatch(item -> item.isFunctionCall() && callId.equals(item.asFunctionCall().callId())))
      return null;
    if (tools.resolveTool(SendReactionAgentTool.TOOL_NAME, message) == null) return null;
    // Reaction is restricted to the enum-like allowlist in DecisionRouting. Omitted message GUID
    // deliberately targets the current incoming message through SendReactionAgentTool.
    var call =
        ResponseFunctionToolCall.builder()
            .callId(callId)
            .name(SendReactionAgentTool.TOOL_NAME)
            .arguments("{\"reaction\":\"" + routing.reaction() + "\"}")
            .build();
    return response(List.of(ResponseOutputItem.ofFunctionCall(call)));
  }

  private static Response silence() {
    var text =
        ResponseOutputText.builder()
            .text(BBMessageAgent.NO_RESPONSE_TEXT)
            .annotations(List.of())
            .build();
    var message =
        ResponseOutputMessage.builder()
            .id("decision_silence")
            .status(ResponseOutputMessage.Status.COMPLETED)
            .content(List.of(ResponseOutputMessage.Content.ofOutputText(text)))
            .build();
    return response(List.of(ResponseOutputItem.ofMessage(message)));
  }

  private static Response response(List<ResponseOutputItem> output) {
    return Response.builder()
        .id("decision_response")
        .createdAt(0.0)
        .error((ResponseError) null)
        .incompleteDetails((Response.IncompleteDetails) null)
        .instructions((Response.Instructions) null)
        .metadata((Response.Metadata) null)
        .model("decision-routing")
        .output(output)
        .parallelToolCalls(false)
        .temperature(0.0)
        .toolChoice(ToolChoiceOptions.AUTO)
        .tools(List.of())
        .topP(1.0)
        .build();
  }
}

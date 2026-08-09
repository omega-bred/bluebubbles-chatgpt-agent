package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationDigestService;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupResult;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.GroupQuestionAnswer;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class GetGroupCatchupAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "get_group_catchup";
  private final MemoryScopeResolver scopeResolver;

  public GetGroupCatchupAgentTool(MemoryScopeResolver scopeResolver) {
    this.scopeResolver = scopeResolver;
  }

  @Schema(description = "Get a complete, time-bounded catch-up for authorized group chats.")
  public record GetGroupCatchupRequest(
      String group,
      String from,
      String to,
      @JsonProperty("lookback_hours") Integer lookbackHours,
      String question) {}

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Get a time-bounded catch-up for group conversations the current user was authorized to"
            + " see. Use for what happened, what did I miss, summaries, decisions, and open"
            + " questions over a requested time range. For precise who, what, which, when, count,"
            + " score, or comparison questions, pass the user's exact question in question. Unlike"
            + " semantic memory search, this returns authoritative question coverage and a coverage"
            + " watermark.",
        jsonSchema(GetGroupCatchupRequest.class),
        false,
        (context, args) -> {
          IncomingMessage message = context.message();
          if (message == null) {
            return "current conversation unavailable";
          }
          String currentChatGuid = IncomingMessage.chatGuidOrNull(message);
          if (message.isGroup() && currentChatGuid == null) {
            return "current group chat unavailable";
          }
          String accountId = context.canonicalAccountId().orElse(null);
          if (accountId == null) {
            return "canonical account unavailable";
          }
          ConversationDigestService digestService =
              scopeResolver.conversationDigestService().orElse(null);
          if (digestService == null) {
            return "group catch-ups unavailable";
          }
          try {
            GetGroupCatchupRequest request =
                context.getMapper().convertValue(args, GetGroupCatchupRequest.class);
            Instant now = digestService.currentTime();
            CatchupRange range = resolveRange(request, now);
            CatchupResult result =
                message.isGroup()
                    ? digestService.catchUpForChat(
                        accountId,
                        message.transportOrDefault(),
                        currentChatGuid,
                        range.from(),
                        range.to(),
                        request.question())
                    : digestService.catchUp(
                        accountId, request.group(), range.from(), range.to(), request.question());
            return ToolJson.stringify(
                context.getMapper(), response(result), "group catch-up serialization failed");
          } catch (DateTimeException | ArithmeticException | IllegalArgumentException e) {
            return "invalid catch-up range";
          }
        });
  }

  private CatchupRange resolveRange(GetGroupCatchupRequest request, Instant now) {
    Instant to = request.to() == null ? now : Instant.parse(request.to());
    Instant from;
    if (request.from() != null) {
      from = Instant.parse(request.from());
    } else {
      int lookbackHours = request.lookbackHours() == null ? 24 : request.lookbackHours();
      if (lookbackHours <= 0) {
        throw new IllegalArgumentException("lookback must be positive");
      }
      from = to.minus(Duration.ofHours(lookbackHours));
    }
    if (!from.isBefore(to) || to.isAfter(now)) {
      throw new IllegalArgumentException("catch-up range must be ordered and not in the future");
    }
    return new CatchupRange(from, to);
  }

  private Map<String, Object> response(CatchupResult result) {
    Map<String, Object> response = new LinkedHashMap<>();
    if (result.ambiguous()) {
      response.put("disambiguation_required", true);
      response.put("group_options", result.disambiguationOptions());
      return response;
    }
    response.put("disambiguation_required", false);
    response.put(
        "groups",
        result.groups().stream()
            .map(
                group -> {
                  Map<String, Object> value = new LinkedHashMap<>();
                  value.put("group", group.group());
                  value.put("summary", group.summary());
                  value.put("key_developments", group.keyDevelopments());
                  value.put("decisions", group.decisions());
                  value.put("open_questions", group.openQuestions());
                  value.put("from", group.from().toString());
                  value.put("to", group.to().toString());
                  value.put("coverage_through", group.coverageThrough().toString());
                  if (group.questionAnswer() != null) {
                    value.put("question_answer", questionAnswerResponse(group.questionAnswer()));
                  }
                  return value;
                })
            .toList());
    return response;
  }

  private Map<String, Object> questionAnswerResponse(GroupQuestionAnswer answer) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("status", answer.status().wireValue());
    value.put("answer", answer.answer());
    value.put("confidence", answer.confidence().wireValue());
    value.put("model", answer.model());
    value.put("fallback_used", answer.fallbackUsed());
    value.put("evidence_message_count", answer.evidenceMessageCount());
    value.put("retrieval_mode", answer.retrievalMode().wireValue());
    value.put("coverage_status", answer.coverageStatus().wireValue());
    value.put("from", answer.from().toString());
    value.put("to", answer.to().toString());
    value.put("coverage_through", answer.coverageThrough().toString());
    if (answer.partialReason() != null) {
      value.put("partial_reason", answer.partialReason());
    }
    return value;
  }

  private record CatchupRange(Instant from, Instant to) {}
}

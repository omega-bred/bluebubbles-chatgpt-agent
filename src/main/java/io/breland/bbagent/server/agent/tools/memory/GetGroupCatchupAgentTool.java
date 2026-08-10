package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationDigestService;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupResult;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.GroupQuestionResult;
import io.breland.bbagent.server.agent.memory.ConversationQuestionAnsweringModels.AnswerStatus;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

public class GetGroupCatchupAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "get_group_catchup";
  private final MemoryScopeResolver scopeResolver;

  public GetGroupCatchupAgentTool(MemoryScopeResolver scopeResolver) {
    this.scopeResolver = scopeResolver;
  }

  @Schema(description = "Ask about group messages or get a time-bounded group summary.")
  public record GetGroupCatchupRequest(
      String group,
      String from,
      String to,
      @Schema(
              description =
                  "Summary-mode lookback in hours. Omit when question is present; relative time stays in the exact question.")
          @JsonProperty("lookback_hours")
          Integer lookbackHours,
      String question,
      String timezone) {}

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Answer a question from a group's recent messages, progressively checking older messages"
            + " when needed. It can ask for an approximate time when more context is needed."
            + " Without a question, return a time-bounded group summary.",
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
            if (StringUtils.isNotBlank(request.question())) {
              QuestionRange range = resolveQuestionRange(request, now);
              GroupQuestionResult result =
                  message.isGroup()
                      ? digestService.answerQuestionForChat(
                          accountId,
                          message.transportOrDefault(),
                          currentChatGuid,
                          request.question(),
                          range.from(),
                          range.to(),
                          range.timezone())
                      : digestService.answerQuestion(
                          accountId,
                          request.group(),
                          request.question(),
                          range.from(),
                          range.to(),
                          range.timezone());
              return ToolJson.stringify(
                  context.getMapper(),
                  questionResponse(result),
                  "group question serialization failed");
            }
            CatchupRange range = resolveCatchupRange(request, now);
            CatchupResult result =
                message.isGroup()
                    ? digestService.catchUpForChat(
                        accountId,
                        message.transportOrDefault(),
                        currentChatGuid,
                        range.from(),
                        range.to())
                    : digestService.catchUp(accountId, request.group(), range.from(), range.to());
            return ToolJson.stringify(
                context.getMapper(),
                summaryResponse(result),
                "group catch-up serialization failed");
          } catch (DateTimeException | ArithmeticException | IllegalArgumentException e) {
            GetGroupCatchupRequest request =
                context.getMapper().convertValue(args, GetGroupCatchupRequest.class);
            return StringUtils.isNotBlank(request.question())
                ? "invalid question range"
                : "invalid catch-up range";
          }
        });
  }

  private CatchupRange resolveCatchupRange(GetGroupCatchupRequest request, Instant now) {
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

  private QuestionRange resolveQuestionRange(GetGroupCatchupRequest request, Instant now) {
    Instant to = request.to() == null ? now : Instant.parse(request.to());
    Instant from = request.from() == null ? null : Instant.parse(request.from());
    if ((from != null && !from.isBefore(to)) || to.isAfter(now)) {
      throw new IllegalArgumentException("question range must be ordered and not in the future");
    }
    String timezone = StringUtils.trimToNull(request.timezone());
    if (timezone != null) {
      ZoneId.of(timezone);
    }
    return new QuestionRange(from, to, timezone);
  }

  private Map<String, Object> summaryResponse(CatchupResult result) {
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
                  return value;
                })
            .toList());
    return response;
  }

  private Map<String, Object> questionResponse(GroupQuestionResult result) {
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
                  if (group.answer().status() == AnswerStatus.CLARIFICATION_REQUIRED) {
                    value.put("clarification_question", group.answer().clarificationQuestion());
                  } else {
                    value.put("answer", group.answer().answer());
                    if (group.answer().status() == AnswerStatus.ANSWERED) {
                      value.put(
                          "unresolved_participants",
                          group.answer().unresolvedParticipants().stream()
                              .map(
                                  hint ->
                                      Map.of(
                                          "label",
                                          hint.label(),
                                          "identity",
                                          hint.normalizedIdentity()))
                              .toList());
                    }
                  }
                  return value;
                })
            .toList());
    return response;
  }

  private record CatchupRange(Instant from, Instant to) {}

  private record QuestionRange(
      @org.springframework.lang.Nullable Instant from,
      Instant to,
      @org.springframework.lang.Nullable String timezone) {}
}

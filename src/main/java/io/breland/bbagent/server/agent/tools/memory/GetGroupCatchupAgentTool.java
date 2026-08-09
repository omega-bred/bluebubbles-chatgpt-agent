package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.memory.ConversationDigestService;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupResult;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
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
      @JsonProperty("lookback_hours") Integer lookbackHours) {}

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Get a time-bounded catch-up for group conversations the current user was authorized to"
            + " see. Use for what happened, what did I miss, summaries, decisions, and open"
            + " questions over a requested time range. Unlike semantic memory search, this returns"
            + " a coverage watermark.",
        jsonSchema(GetGroupCatchupRequest.class),
        false,
        (context, args) -> {
          if (context.message() == null || context.message().isGroup()) {
            return "available only in a one-to-one chat";
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
          GetGroupCatchupRequest request =
              context.getMapper().convertValue(args, GetGroupCatchupRequest.class);
          try {
            Instant now = digestService.currentTime();
            Instant to = request.to() == null ? now : Instant.parse(request.to());
            int lookbackHours =
                request.lookbackHours() == null
                    ? 24
                    : Math.max(1, Math.min(31 * 24, request.lookbackHours()));
            Instant from =
                request.from() == null
                    ? to.minus(Duration.ofHours(lookbackHours))
                    : Instant.parse(request.from());
            CatchupResult result = digestService.catchUp(accountId, request.group(), from, to);
            return ToolJson.stringify(
                context.getMapper(), response(result), "group catch-up serialization failed");
          } catch (IllegalArgumentException e) {
            return "invalid catch-up range";
          }
        });
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
                  return value;
                })
            .toList());
    return response;
  }
}

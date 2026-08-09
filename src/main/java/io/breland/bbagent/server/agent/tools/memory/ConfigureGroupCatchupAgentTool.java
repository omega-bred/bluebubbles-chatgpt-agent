package io.breland.bbagent.server.agent.tools.memory;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupPreferenceSetting;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupPreferenceUpdate;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.memory.ProactiveCatchupService;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConfigureGroupCatchupAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "configure_group_catchup";
  private final MemoryScopeResolver scopeResolver;

  public ConfigureGroupCatchupAgentTool(MemoryScopeResolver scopeResolver) {
    this.scopeResolver = scopeResolver;
  }

  @Schema(description = "Configure proactive personal catch-ups for an authorized group.")
  public record ConfigureGroupCatchupRequest(
      String group,
      Boolean enabled,
      String timezone,
      @JsonProperty("quiet_start") String quietStart,
      @JsonProperty("quiet_end") String quietEnd) {}

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Enable or disable proactive one-to-one summaries for a group the current user is"
            + " authorized to access. The preference is personal and group-specific. Quiet hours"
            + " use an IANA timezone and HH:mm values.",
        jsonSchema(ConfigureGroupCatchupRequest.class),
        false,
        (context, args) -> {
          if (context.message() == null || context.message().isGroup()) {
            return "available only in a one-to-one chat";
          }
          String accountId = context.canonicalAccountId().orElse(null);
          if (accountId == null) {
            return "canonical account unavailable";
          }
          ProactiveCatchupService service = scopeResolver.proactiveCatchupService().orElse(null);
          if (service == null) {
            return "personal group catch-ups unavailable";
          }
          ConfigureGroupCatchupRequest request =
              context.getMapper().convertValue(args, ConfigureGroupCatchupRequest.class);
          if (request.enabled() == null) {
            return "enabled is required";
          }
          try {
            CatchupPreferenceUpdate result =
                service.updateForGroup(
                    accountId,
                    request.group(),
                    request.enabled(),
                    request.timezone(),
                    request.quietStart(),
                    request.quietEnd());
            return ToolJson.stringify(
                context.getMapper(), response(result), "catch-up preference serialization failed");
          } catch (IllegalArgumentException e) {
            return "invalid catch-up preference";
          }
        });
  }

  private Map<String, Object> response(CatchupPreferenceUpdate result) {
    Map<String, Object> response = new LinkedHashMap<>();
    if (result.ambiguous()) {
      response.put("disambiguation_required", true);
      response.put("group_options", result.disambiguationOptions());
      return response;
    }
    CatchupPreferenceSetting setting = result.setting();
    response.put("disambiguation_required", false);
    response.put("enabled", setting.enabled());
    response.put("group", setting.groupDisplayName());
    response.put("timezone", setting.timezone());
    response.put("quiet_start", setting.quietStart());
    response.put("quiet_end", setting.quietEnd());
    response.put(
        "user_facing_text",
        setting.enabled()
            ? "Personal catch-ups are on. BlueChatAI may send developments since your last"
                + " catch-up in "
                + setting.groupDisplayName()
                + " outside quiet hours "
                + setting.quietStart()
                + "–"
                + setting.quietEnd()
                + " ("
                + setting.timezone()
                + ")."
            : "Personal catch-ups are off for " + setting.groupDisplayName() + ".");
    return response;
  }
}

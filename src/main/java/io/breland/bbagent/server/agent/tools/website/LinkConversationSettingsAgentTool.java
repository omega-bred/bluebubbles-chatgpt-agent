package io.breland.bbagent.server.agent.tools.website;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.website.WebsiteAccountService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class LinkConversationSettingsAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "link_conversation_settings";
  private static final DateTimeFormatter INSTANT_FORMATTER = DateTimeFormatter.ISO_INSTANT;

  private final WebsiteAccountService accountService;

  @Schema(
      description = "Create a conversation settings link for the current BlueChat conversation.")
  public record LinkConversationSettingsRequest(
      @Schema(description = "Short reason the user wants conversation settings.")
          @JsonProperty("purpose")
          String purpose) {}

  public LinkConversationSettingsAgentTool(WebsiteAccountService accountService) {
    this.accountService = accountService;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Create a short-lived conversation settings link for the current chat. Use when the user"
            + " asks to open conversation settings, manage this chat's assistant responsiveness,"
            + " or configure whether the assistant should be silent, conservative, balanced, or active.",
        jsonSchema(LinkConversationSettingsRequest.class),
        false,
        (context, args) -> {
          if (context.message() == null) {
            return "missing message context";
          }
          try {
            WebsiteAccountService.CreatedLinkToken link =
                accountService.createConversationSettingsToken(context.message());
            return ToolJson.stringify(
                context.getMapper(), toResponse(link), "error: unable to encode settings link");
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }

  private Map<String, Object> toResponse(WebsiteAccountService.CreatedLinkToken link) {
    String expiresAt = INSTANT_FORMATTER.format(link.expiresAt());
    String text =
        "Open this link to manage BlueChatAI responsiveness for this conversation: "
            + link.url()
            + "\nThis link expires at "
            + expiresAt
            + ".";
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("link_url", link.url());
    response.put("expires_at", expiresAt);
    response.put("account_id", link.accountId());
    response.put("chat_guid", link.chatGuid());
    response.put("user_facing_text", text);
    return response;
  }
}

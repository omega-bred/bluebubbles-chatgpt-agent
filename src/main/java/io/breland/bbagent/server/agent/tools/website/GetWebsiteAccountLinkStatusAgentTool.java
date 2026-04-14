package io.breland.bbagent.server.agent.tools.website;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.StringValueUtils;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.website.WebsiteAccountService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class GetWebsiteAccountLinkStatusAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "get_website_account_link_status";
  private static final DateTimeFormatter INSTANT_FORMATTER = DateTimeFormatter.ISO_INSTANT;

  private final WebsiteAccountService accountService;

  @Schema(description = "Check whether an iMessage sender is linked to a website account.")
  public record GetWebsiteAccountLinkStatusRequest(
      @Schema(
              description =
                  "Optional iMessage sender/handle to check. Defaults to the current incoming sender.")
          @JsonProperty("sender")
          String sender,
      @Schema(
              description =
                  "Optional chat GUID to check for chat-scoped integrations. Defaults to the current incoming chat.")
          @JsonProperty("chat_guid")
          String chatGuid) {}

  public GetWebsiteAccountLinkStatusAgentTool(WebsiteAccountService accountService) {
    this.accountService = accountService;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Check whether the current or specified iMessage sender is already linked to a website account. Use this before answering account-link status questions; if unlinked and the user wants to connect, call link_website_account next.",
        jsonSchema(GetWebsiteAccountLinkStatusRequest.class),
        false,
        (context, args) -> {
          try {
            GetWebsiteAccountLinkStatusRequest request =
                context.getMapper().convertValue(args, GetWebsiteAccountLinkStatusRequest.class);
            IncomingMessage message = context.message();
            String sender =
                StringValueUtils.firstNonBlank(
                    request.sender(), message == null ? null : message.sender());
            String chatGuid =
                StringValueUtils.firstNonBlank(
                    request.chatGuid(), message == null ? null : message.chatGuid());
            WebsiteAccountService.SenderLinkStatus status =
                accountService.getLinkStatus(sender, chatGuid);
            return context.getMapper().writeValueAsString(toResponse(status));
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }

  private Map<String, Object> toResponse(WebsiteAccountService.SenderLinkStatus status) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("linked", status.linked());
    response.put("exact_chat_linked", status.exactChatLinked());
    response.put("account_base", status.accountBase());
    response.put("coder_account_base", status.coderAccountBase());
    response.put("gcal_account_base", status.gcalAccountBase());
    response.put("link_count", status.linkCount());
    response.put("exact_chat_link_count", status.exactChatLinkCount());
    response.put("model_access", status.modelAccess());
    response.put(
        "linked_at",
        status.linkedAt() == null ? null : INSTANT_FORMATTER.format(status.linkedAt()));
    response.put("user_facing_text", userFacingText(status));
    return response;
  }

  private String userFacingText(WebsiteAccountService.SenderLinkStatus status) {
    if (status.accountBase() == null || status.accountBase().isBlank()) {
      return "I could not tell which iMessage sender to check.";
    }
    if (status.exactChatLinked()) {
      return "This iMessage sender is linked to a web account for this chat. " + modelText(status);
    }
    if (status.linked()) {
      return "This iMessage sender is linked to a web account, but this specific chat does not have its own web account link yet. "
          + modelText(status);
    }
    return "This iMessage sender is not linked to a web account yet. " + modelText(status);
  }

  private String modelText(WebsiteAccountService.SenderLinkStatus status) {
    if (status.modelAccess() == null) {
      return "Model access could not be determined.";
    }
    String plan = Boolean.TRUE.equals(status.modelAccess().getIsPremium()) ? "premium" : "standard";
    return "Model access: "
        + plan
        + ", current model: "
        + status.modelAccess().getCurrentModelLabel()
        + ".";
  }
}

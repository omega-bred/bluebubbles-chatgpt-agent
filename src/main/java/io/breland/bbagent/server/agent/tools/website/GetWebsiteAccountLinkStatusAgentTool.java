package io.breland.bbagent.server.agent.tools.website;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.website.WebsiteAccountService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

public class GetWebsiteAccountLinkStatusAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "get_website_account_link_status";
  private static final DateTimeFormatter INSTANT_FORMATTER = DateTimeFormatter.ISO_INSTANT;

  private final WebsiteAccountService accountService;

  @Schema(description = "Check whether a chat identity is linked to a website account.")
  public record GetWebsiteAccountLinkStatusRequest(
      @Schema(
              description =
                  "Optional sender/handle to check. Defaults to the current incoming sender.")
          @JsonProperty("sender")
          String sender,
      @Schema(
              description =
                  "Optional chat GUID to check for chat-scoped integrations. Defaults to the"
                      + " current incoming chat.")
          @JsonProperty("chat_guid")
          String chatGuid,
      @Schema(
              description =
                  "Optional transport to check, such as bluebubbles or lxmf. Defaults to the"
                      + " current incoming message transport.")
          @JsonProperty("transport")
          String transport) {}

  public GetWebsiteAccountLinkStatusAgentTool(WebsiteAccountService accountService) {
    this.accountService = accountService;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Check whether the current or specified chat identity is already linked to a website"
            + " account. Use this before answering account-link status questions; if unlinked and"
            + " the user wants to connect, call link_website_account next.",
        jsonSchema(GetWebsiteAccountLinkStatusRequest.class),
        false,
        (context, args) -> {
          try {
            GetWebsiteAccountLinkStatusRequest request =
                context.getMapper().convertValue(args, GetWebsiteAccountLinkStatusRequest.class);
            IncomingMessage message = context.message();
            WebsiteAccountService.SenderLinkStatus status = getLinkStatus(request, message);
            return ToolJson.stringify(
                context.getMapper(), toResponse(status), "error: unable to encode link status");
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }

  private WebsiteAccountService.SenderLinkStatus getLinkStatus(
      GetWebsiteAccountLinkStatusRequest request, IncomingMessage message) {
    boolean hasExplicitLookup =
        StringUtils.isNotBlank(request.sender())
            || StringUtils.isNotBlank(request.chatGuid())
            || StringUtils.isNotBlank(request.transport());
    if (!hasExplicitLookup && message != null) {
      return accountService.getLinkStatus(message);
    }
    String sender =
        StringUtils.firstNonBlank(request.sender(), message == null ? null : message.sender());
    String chatGuid =
        StringUtils.firstNonBlank(request.chatGuid(), message == null ? null : message.chatGuid());
    String transport =
        StringUtils.firstNonBlank(
            request.transport(), message == null ? null : message.transportOrDefault());
    return accountService.getLinkStatus(transport, sender, chatGuid);
  }

  private Map<String, Object> toResponse(WebsiteAccountService.SenderLinkStatus status) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("linked", status.linked());
    response.put("exact_chat_linked", status.exactChatLinked());
    response.put("account_id", status.accountId());
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
    if (StringUtils.isBlank(status.accountId())) {
      return "I could not tell which sender or chat identity to check.";
    }
    if (status.exactChatLinked()) {
      return "This chat identity is linked to a web account for this chat. " + modelText(status);
    }
    if (status.linked()) {
      return "This chat identity is linked to a web account, but this specific chat does not have"
          + " its own web account link yet. "
          + modelText(status);
    }
    return "This chat identity is not linked to a web account yet. " + modelText(status);
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

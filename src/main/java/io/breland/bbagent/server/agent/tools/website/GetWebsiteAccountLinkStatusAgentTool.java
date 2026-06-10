package io.breland.bbagent.server.agent.tools.website;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.JsonSchemaUtilities;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

public class GetWebsiteAccountLinkStatusAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "get_website_account_link_status";

  private final WebsiteAccountService accountService;

  public GetWebsiteAccountLinkStatusAgentTool(WebsiteAccountService accountService) {
    this.accountService = accountService;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Check whether the current chat identity is already linked to a website"
            + " account. Use this before answering account-link status questions; if unlinked and"
            + " the user wants to connect, call link_website_account next.",
        JsonSchemaUtilities.functionParameters(
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)),
        false,
        (context, args) -> {
          try {
            IncomingMessage message = context.message();
            WebsiteAccountService.SenderLinkStatus status = accountService.getLinkStatus(message);
            return ToolJson.stringify(
                context.getMapper(), toResponse(status), "error: unable to encode link status");
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  private record LinkStatusResponse(
      boolean linked,
      boolean exactChatLinked,
      String accountId,
      int linkCount,
      int exactChatLinkCount,
      WebsiteModelAccessSummary modelAccess,
      String linkedAt,
      String userFacingText) {}

  private LinkStatusResponse toResponse(WebsiteAccountService.SenderLinkStatus status) {
    return new LinkStatusResponse(
        status.linked(),
        status.exactChatLinked(),
        status.accountId(),
        status.linkCount(),
        status.exactChatLinkCount(),
        status.modelAccess(),
        status.linkedAt() == null ? null : status.linkedAt().toString(),
        userFacingText(status));
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
    String accountType =
        Boolean.TRUE.equals(status.modelAccess().getIsPremium())
            ? "premium account"
            : "free account";
    return "Model access: "
        + accountType
        + ", current model: "
        + status.modelAccess().getCurrentModelLabel()
        + ".";
  }
}

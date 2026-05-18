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

public class LinkWebsiteAccountAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "link_website_account";
  private static final DateTimeFormatter INSTANT_FORMATTER = DateTimeFormatter.ISO_INSTANT;

  private final WebsiteAccountService accountService;

  @Schema(description = "Create a website account link for the current chat identity.")
  public record LinkWebsiteAccountRequest(
      @Schema(description = "Short reason the user wants the account link.")
          @JsonProperty("purpose")
          String purpose) {}

  public LinkWebsiteAccountAgentTool(WebsiteAccountService accountService) {
    this.accountService = accountService;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Create a short-lived website login/signup link that connects the current chat identity to"
            + " a website account. Use when the user asks to log in, manage their website account,"
            + " link this chat identity to the website, or view web account integrations.",
        jsonSchema(LinkWebsiteAccountRequest.class),
        false,
        (context, args) -> {
          if (context.message() == null) {
            return "missing message context";
          }
          try {
            WebsiteAccountService.CreatedLinkToken link =
                accountService.createLinkToken(context.message());
            return ToolJson.stringify(
                context.getMapper(), toResponse(link), "error: unable to encode link");
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }

  private Map<String, Object> toResponse(WebsiteAccountService.CreatedLinkToken link) {
    String expiresAt = INSTANT_FORMATTER.format(link.expiresAt());
    String text =
        "Open this link to log in or sign up and connect this chat identity to your web account: "
            + link.url()
            + "\nThis link expires at "
            + expiresAt
            + ".";
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("link_url", link.url());
    response.put("expires_at", expiresAt);
    response.put("account_id", link.accountId());
    response.put("user_facing_text", text);
    return response;
  }
}

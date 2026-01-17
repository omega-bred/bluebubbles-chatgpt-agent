package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;

public class ManageAccountsAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "manage_accounts";

  @Schema(description = "Manage Google Calendar account links.")
  public record ManageAccountsRequest(
      @Schema(
              description = "Action to perform.",
              allowableValues = {"list", "auth_url", "revoke"},
              requiredMode = Schema.RequiredMode.REQUIRED)
          Action action,
      @Schema(description = "Account key for revoke operations.") @JsonProperty("account_key")
          String accountKey) {}

  public enum Action {
    @JsonProperty("list")
    LIST,
    @JsonProperty("auth_url")
    AUTH_URL,
    @JsonProperty("revoke")
    REVOKE
  }

  public ManageAccountsAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Manage Google Calendar accounts (auth URL, list, revoke). Use this tool to find out if a calendar/account is already linked and other account management operations. Use account_key to target a specific linked account. Always use this tool to make a new account link URL - never re-use an existing URL.",
        jsonSchema(ManageAccountsRequest.class),
        false,
        (context, args) -> {
          if (!gcalClient.isConfigured()) {
            return "not configured";
          }
          ManageAccountsRequest request =
              context.getMapper().convertValue(args, ManageAccountsRequest.class);
          if (request.action() == null) {
            return "missing action";
          }
          try {
            switch (request.action()) {
              case LIST -> {
                String accountBase = resolveAccountBase(context);
                if (accountBase == null || accountBase.isBlank()) {
                  return "no account";
                }
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("accounts", gcalClient.listAccountsFor(accountBase));
                return gcalClient.mapper().writeValueAsString(response);
              }
              case AUTH_URL -> {
                String accountBase = resolveAccountBase(context);
                if (accountBase == null || accountBase.isBlank()) {
                  return "no account";
                }
                String accountKey = accountBase;
                String chatGuid = context.message() != null ? context.message().chatGuid() : null;
                String messageGuid =
                    context.message() != null ? context.message().messageGuid() : null;
                if (chatGuid == null || chatGuid.isBlank()) {
                  return "missing chat";
                }
                String url = gcalClient.getAuthUrl(accountKey, chatGuid, messageGuid);
                if (url == null) {
                  return "not configured";
                }
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("auth_url", url);
                response.put("account_key", "new");
                return gcalClient.mapper().writeValueAsString(response);
              }
              case REVOKE -> {
                String accountBase = resolveAccountBase(context);
                if (accountBase == null || accountBase.isBlank()) {
                  return "no account";
                }
                String requestedAccountKey = request.accountKey();
                String accountKey = gcalClient.scopeAccountKey(accountBase, requestedAccountKey);
                boolean success = gcalClient.revokeAccount(accountKey);
                return success ? "revoked" : "not found";
              }
              default -> {
                return "unknown action";
              }
            }
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }
}

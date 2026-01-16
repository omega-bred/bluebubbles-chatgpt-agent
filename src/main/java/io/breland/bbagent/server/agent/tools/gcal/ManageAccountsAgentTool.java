package io.breland.bbagent.server.agent.tools.gcal;

import static io.breland.bbagent.server.agent.BBMessageAgent.getOptionalText;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ManageAccountsAgentTool extends GcalToolSupport implements ToolProvider {
  public static final String TOOL_NAME = "manage_accounts";

  public ManageAccountsAgentTool(GcalClient gcalClient) {
    super(gcalClient);
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Manage Google Calendar accounts (auth URL, list, revoke). Use this tool to find out if a calendar/account is already linked and other account management operations. Use account_key to target a specific linked account.",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "action",
                    Map.of("type", "string", "enum", List.of("list", "auth_url", "revoke")),
                    "account_key",
                    Map.of("type", "string")),
                "required",
                List.of("action"))),
        false,
        (context, args) -> {
          if (!gcalClient.isConfigured()) {
            return "not configured";
          }
          String action = getOptionalText(args, "action");
          if (action == null || action.isBlank()) {
            return "missing action";
          }
          try {
            switch (action) {
              case "list" -> {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("accounts", gcalClient.listAccounts());
                return gcalClient.mapper().writeValueAsString(response);
              }
              case "auth_url" -> {
                String requestedAccountKey = getOptionalText(args, "account_key");
                String accountKey =
                    requestedAccountKey != null && !requestedAccountKey.isBlank()
                        ? requestedAccountKey
                        : resolveAccountKey(context);
                if (accountKey == null || accountKey.isBlank()) {
                  return "no account";
                }
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
                response.put("account_key", accountKey);
                return gcalClient.mapper().writeValueAsString(response);
              }
              case "revoke" -> {
                String requestedAccountKey = getOptionalText(args, "account_key");
                String accountKey =
                    requestedAccountKey != null && !requestedAccountKey.isBlank()
                        ? requestedAccountKey
                        : resolveAccountKey(context);
                if (accountKey == null || accountKey.isBlank()) {
                  return "no account";
                }
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

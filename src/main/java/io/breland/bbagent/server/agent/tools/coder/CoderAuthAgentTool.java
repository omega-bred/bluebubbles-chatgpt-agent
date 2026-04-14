package io.breland.bbagent.server.agent.tools.coder;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;

public class CoderAuthAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "coder_auth";

  private final CoderMcpClient coderMcpClient;

  @Schema(description = "Connect, check, or revoke the user's Coder MCP OAuth login.")
  public record CoderAuthRequest(
      @Schema(
              description = "Action to perform.",
              allowableValues = {"status", "auth_url", "revoke"},
              requiredMode = Schema.RequiredMode.REQUIRED)
          Action action) {}

  public enum Action {
    @JsonProperty("status")
    STATUS,
    @JsonProperty("auth_url")
    AUTH_URL,
    @JsonProperty("revoke")
    REVOKE
  }

  public CoderAuthAgentTool(CoderMcpClient coderMcpClient) {
    this.coderMcpClient = coderMcpClient;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Connect, check, or revoke Coder OAuth. This is only an auth/status tool; use the injected coder__ tools for Coder work after Coder is linked.",
        jsonSchema(CoderAuthRequest.class),
        false,
        (context, args) -> {
          if (!coderMcpClient.isConfigured()) {
            return "not configured";
          }
          CoderAuthRequest request = context.getMapper().convertValue(args, CoderAuthRequest.class);
          if (request.action() == null) {
            return "missing action";
          }
          String accountBase = CoderMcpClient.resolveAccountBase(context);
          if (accountBase == null || accountBase.isBlank()) {
            return "no account";
          }
          try {
            return switch (request.action()) {
              case STATUS -> {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("linked", coderMcpClient.isLinked(accountBase));
                response.put("tool_prefix", CoderMcpClient.TOOL_PREFIX);
                yield context.getMapper().writeValueAsString(response);
              }
              case AUTH_URL -> {
                if (coderMcpClient.isLinked(accountBase)) {
                  Map<String, Object> response = new LinkedHashMap<>();
                  response.put("linked", true);
                  yield context.getMapper().writeValueAsString(response);
                }
                String chatGuid = context.message() != null ? context.message().chatGuid() : null;
                String messageGuid =
                    context.message() != null ? context.message().messageGuid() : null;
                if (chatGuid == null || chatGuid.isBlank()) {
                  yield "missing chat";
                }
                var authUrl = coderMcpClient.getAuthUrl(accountBase, chatGuid, messageGuid);
                if (authUrl.isEmpty()) {
                  yield "not configured";
                }
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("auth_url", authUrl.get());
                response.put("linked", false);
                yield context.getMapper().writeValueAsString(response);
              }
              case REVOKE -> coderMcpClient.revoke(accountBase) ? "revoked" : "not found";
            };
          } catch (Exception e) {
            return "error: " + e.getMessage();
          }
        });
  }
}

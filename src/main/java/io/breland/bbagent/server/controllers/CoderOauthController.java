package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.api.CoderApiController;
import io.breland.bbagent.server.agent.tools.coder.CoderMcpClient;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class CoderOauthController extends CoderApiController {
  private final CoderMcpClient coderMcpClient;
  private final OauthCallbackSupport oauthCallbackSupport;

  public CoderOauthController(
      NativeWebRequest request,
      CoderMcpClient coderMcpClient,
      OauthCallbackSupport oauthCallbackSupport) {
    super(request);
    this.coderMcpClient = coderMcpClient;
    this.oauthCallbackSupport = oauthCallbackSupport;
  }

  @Override
  public ResponseEntity<String> coderCompleteOauth(String code, String state, String error) {
    if (error != null && !error.isBlank()) {
      return htmlResponse(HttpStatus.BAD_REQUEST, "Coder OAuth was not approved: " + error);
    }
    if (code == null || code.isBlank() || state == null || state.isBlank()) {
      return htmlResponse(
          HttpStatus.BAD_REQUEST, "Missing required OAuth parameters. Please try again.");
    }
    if (!coderMcpClient.isConfigured()) {
      return htmlResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Coder MCP is not configured.");
    }
    Optional<CoderMcpClient.OauthCompletion> completion = coderMcpClient.completeOauth(code, state);
    if (completion.isEmpty()) {
      return htmlResponse(HttpStatus.INTERNAL_SERVER_ERROR, "OAuth failed. Please try again.");
    }
    oauthCallbackSupport.sendFollowup(
        completion.get().chatGuid(), completion.get().messageGuid(), "Coder successfully linked.");
    return htmlResponse(HttpStatus.OK, "Coder linked. You can close this tab.");
  }

  private ResponseEntity<String> htmlResponse(HttpStatus status, String message) {
    return oauthCallbackSupport.htmlResponse("Coder OAuth", status, message);
  }
}

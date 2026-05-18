package io.breland.bbagent.server.controllers;

import static io.breland.bbagent.server.controllers.OauthControllerSupport.errorResponse;
import static io.breland.bbagent.server.controllers.OauthControllerSupport.sendFollowup;
import static io.breland.bbagent.server.controllers.OauthControllerSupport.successResponse;

import io.breland.bbagent.generated.api.CoderApiController;
import io.breland.bbagent.server.agent.tools.coder.CoderMcpClient;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class CoderOauthController extends CoderApiController {
  private static final String SERVICE = "coder";

  private final CoderMcpClient coderMcpClient;
  private final BBHttpClientWrapper bbHttpClientWrapper;

  public CoderOauthController(
      NativeWebRequest request,
      CoderMcpClient coderMcpClient,
      BBHttpClientWrapper bbHttpClientWrapper) {
    super(request);
    this.coderMcpClient = coderMcpClient;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  @Override
  public ResponseEntity<Void> coderCompleteOauth(String code, String state, String error) {
    if (error != null && !error.isBlank()) {
      return errorResponse(SERVICE, "Coder OAuth was not approved: " + error);
    }
    if (code == null || code.isBlank() || state == null || state.isBlank()) {
      return errorResponse(SERVICE, "Missing required OAuth parameters. Please try again.");
    }
    if (!coderMcpClient.isConfigured()) {
      return errorResponse(SERVICE, "Coder MCP is not configured.");
    }
    Optional<CoderMcpClient.OauthCompletion> completion = coderMcpClient.completeOauth(code, state);
    if (completion.isEmpty()) {
      return errorResponse(SERVICE, "OAuth failed. Please try again.");
    }
    sendFollowup(
        bbHttpClientWrapper,
        completion.get().chatGuid(),
        completion.get().messageGuid(),
        "Coder successfully linked.");
    return successResponse(SERVICE, "Coder linked. You can close this tab.");
  }
}

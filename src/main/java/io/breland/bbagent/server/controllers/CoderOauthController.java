package io.breland.bbagent.server.controllers;

import static org.apache.commons.lang3.StringUtils.isAnyBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import io.breland.bbagent.generated.api.CoderApiController;
import io.breland.bbagent.server.agent.tools.coder.CoderMcpClient;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class CoderOauthController extends CoderApiController {
  private static final String PAGE_TITLE = "Coder OAuth";

  private final CoderMcpClient coderMcpClient;
  private final OauthCallbackSupport callbackSupport;

  public CoderOauthController(
      NativeWebRequest request,
      CoderMcpClient coderMcpClient,
      BBHttpClientWrapper bbHttpClientWrapper) {
    super(request);
    this.coderMcpClient = coderMcpClient;
    this.callbackSupport = new OauthCallbackSupport(bbHttpClientWrapper);
  }

  @Override
  public ResponseEntity<String> coderCompleteOauth(String code, String state, String error) {
    if (isNotBlank(error)) {
      return callbackSupport.htmlResponse(
          HttpStatus.BAD_REQUEST, PAGE_TITLE, "Coder OAuth was not approved: " + error);
    }
    if (isAnyBlank(code, state)) {
      return callbackSupport.htmlResponse(
          HttpStatus.BAD_REQUEST,
          PAGE_TITLE,
          "Missing required OAuth parameters. Please try again.");
    }
    if (!coderMcpClient.isConfigured()) {
      return callbackSupport.htmlResponse(
          HttpStatus.INTERNAL_SERVER_ERROR, PAGE_TITLE, "Coder MCP is not configured.");
    }
    Optional<CoderMcpClient.OauthCompletion> completion = coderMcpClient.completeOauth(code, state);
    if (completion.isEmpty()) {
      return callbackSupport.htmlResponse(
          HttpStatus.INTERNAL_SERVER_ERROR, PAGE_TITLE, "OAuth failed. Please try again.");
    }
    callbackSupport.sendFollowup(
        completion.get().chatGuid(), completion.get().messageGuid(), "Coder successfully linked.");
    return callbackSupport.htmlResponse(
        HttpStatus.OK, PAGE_TITLE, "Coder linked. You can close this tab.");
  }
}

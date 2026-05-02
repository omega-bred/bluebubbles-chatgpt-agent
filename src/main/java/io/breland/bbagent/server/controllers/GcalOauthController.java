package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.api.GcalApiController;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class GcalOauthController extends GcalApiController {
  private final GcalClient gcalClient;
  private final OauthCallbackSupport oauthCallbackSupport;

  public GcalOauthController(
      NativeWebRequest request, GcalClient gcalClient, OauthCallbackSupport oauthCallbackSupport) {
    super(request);
    this.gcalClient = gcalClient;
    this.oauthCallbackSupport = oauthCallbackSupport;
  }

  @Override
  public ResponseEntity<String> gcalCompleteOauth(String code, String state) {
    if (code == null || code.isBlank() || state == null || state.isBlank()) {
      return htmlResponse(
          HttpStatus.BAD_REQUEST, "Missing required OAuth parameters. Please try again.");
    }
    if (!gcalClient.isConfigured()) {
      return htmlResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Google Calendar is not configured.");
    }
    Optional<GcalClient.OauthState> oauthState = gcalClient.parseOauthState(state);
    if (oauthState.isEmpty()) {
      return htmlResponse(
          HttpStatus.BAD_REQUEST, "Invalid OAuth state. Please retry the linking flow.");
    }
    Optional<String> accountId =
        gcalClient.exchangeCode(
            oauthState.get().accountBase(), oauthState.get().pendingKey(), code);
    if (accountId.isEmpty()) {
      oauthCallbackSupport.sendFollowup(
          oauthState.get().chatGuid(),
          oauthState.get().messageGuid(),
          "Calendar linking failed. Please try again.");
      return htmlResponse(HttpStatus.INTERNAL_SERVER_ERROR, "OAuth failed. Please try again.");
    }
    oauthCallbackSupport.sendFollowup(
        oauthState.get().chatGuid(),
        oauthState.get().messageGuid(),
        "Calendar successfully linked.");
    return htmlResponse(HttpStatus.OK, "Google Calendar linked. You can close this tab.");
  }

  private ResponseEntity<String> htmlResponse(HttpStatus status, String message) {
    return oauthCallbackSupport.htmlResponse("Google Calendar OAuth", status, message);
  }
}

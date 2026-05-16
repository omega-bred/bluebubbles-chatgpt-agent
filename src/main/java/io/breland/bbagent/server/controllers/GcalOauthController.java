package io.breland.bbagent.server.controllers;

import static io.breland.bbagent.server.controllers.OauthControllerSupport.errorResponse;
import static io.breland.bbagent.server.controllers.OauthControllerSupport.sendFollowup;
import static io.breland.bbagent.server.controllers.OauthControllerSupport.successResponse;

import io.breland.bbagent.generated.api.GcalApiController;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class GcalOauthController extends GcalApiController {
  private static final String SERVICE = "gcal";

  private final GcalClient gcalClient;
  private final BBHttpClientWrapper bbHttpClientWrapper;

  public GcalOauthController(
      NativeWebRequest request, GcalClient gcalClient, BBHttpClientWrapper bbHttpClientWrapper) {
    super(request);
    this.gcalClient = gcalClient;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  @Override
  public ResponseEntity<Void> gcalCompleteOauth(String code, String state, String error) {
    if (error != null && !error.isBlank()) {
      return errorResponse(SERVICE, "Google Calendar OAuth was not approved: " + error);
    }
    if (code == null || code.isBlank() || state == null || state.isBlank()) {
      return errorResponse(SERVICE, "Missing required OAuth parameters. Please try again.");
    }
    if (!gcalClient.isConfigured()) {
      return errorResponse(SERVICE, "Google Calendar is not configured.");
    }
    Optional<GcalClient.OauthState> oauthState = gcalClient.parseOauthState(state);
    if (oauthState.isEmpty()) {
      return errorResponse(SERVICE, "Invalid OAuth state. Please retry the linking flow.");
    }
    Optional<String> accountId =
        gcalClient.exchangeCode(
            oauthState.get().accountBase(), oauthState.get().pendingKey(), code);
    if (accountId.isEmpty()) {
      sendFollowup(
          bbHttpClientWrapper,
          oauthState.get().chatGuid(),
          oauthState.get().messageGuid(),
          "Calendar linking failed. Please try again.");
      return errorResponse(SERVICE, "OAuth failed. Please try again.");
    }
    sendFollowup(
        bbHttpClientWrapper,
        oauthState.get().chatGuid(),
        oauthState.get().messageGuid(),
        "Calendar successfully linked.");
    return successResponse(SERVICE, "Google Calendar linked. You can close this tab.");
  }
}

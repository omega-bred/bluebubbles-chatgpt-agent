package io.breland.bbagent.server.controllers;

import static org.apache.commons.lang3.StringUtils.isAnyBlank;

import io.breland.bbagent.generated.api.GcalApiController;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class GcalOauthController extends GcalApiController {
  private static final String PAGE_TITLE = "Google Calendar OAuth";

  private final GcalClient gcalClient;
  private final OauthCallbackSupport callbackSupport;

  public GcalOauthController(
      NativeWebRequest request, GcalClient gcalClient, BBHttpClientWrapper bbHttpClientWrapper) {
    super(request);
    this.gcalClient = gcalClient;
    this.callbackSupport = new OauthCallbackSupport(bbHttpClientWrapper);
  }

  @Override
  public ResponseEntity<String> gcalCompleteOauth(String code, String state) {
    if (isAnyBlank(code, state)) {
      return callbackSupport.htmlResponse(
          HttpStatus.BAD_REQUEST,
          PAGE_TITLE,
          "Missing required OAuth parameters. Please try again.");
    }
    if (!gcalClient.isConfigured()) {
      return callbackSupport.htmlResponse(
          HttpStatus.INTERNAL_SERVER_ERROR, PAGE_TITLE, "Google Calendar is not configured.");
    }
    Optional<GcalClient.OauthState> oauthState = gcalClient.parseOauthState(state);
    if (oauthState.isEmpty()) {
      return callbackSupport.htmlResponse(
          HttpStatus.BAD_REQUEST,
          PAGE_TITLE,
          "Invalid OAuth state. Please retry the linking flow.");
    }
    Optional<String> accountId =
        gcalClient.exchangeCode(
            oauthState.get().accountBase(), oauthState.get().pendingKey(), code);
    if (accountId.isEmpty()) {
      callbackSupport.sendFollowup(
          oauthState.get().chatGuid(),
          oauthState.get().messageGuid(),
          "Calendar linking failed. Please try again.");
      return callbackSupport.htmlResponse(
          HttpStatus.INTERNAL_SERVER_ERROR, PAGE_TITLE, "OAuth failed. Please try again.");
    }
    callbackSupport.sendFollowup(
        oauthState.get().chatGuid(),
        oauthState.get().messageGuid(),
        "Calendar successfully linked.");
    return callbackSupport.htmlResponse(
        HttpStatus.OK, PAGE_TITLE, "Google Calendar linked. You can close this tab.");
  }
}

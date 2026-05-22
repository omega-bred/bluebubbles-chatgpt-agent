package io.breland.bbagent.server.controllers;

import static io.breland.bbagent.server.controllers.OauthControllerSupport.errorResponse;
import static io.breland.bbagent.server.controllers.OauthControllerSupport.sendFollowup;
import static io.breland.bbagent.server.controllers.OauthControllerSupport.successResponse;

import io.breland.bbagent.generated.api.GcalApiController;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.analytics.UmamiAnalyticsService;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class GcalOauthController extends GcalApiController {
  private static final String SERVICE = "gcal";

  private final GcalClient gcalClient;
  private final BBHttpClientWrapper bbHttpClientWrapper;
  private final UmamiAnalyticsService umamiAnalyticsService;

  public GcalOauthController(
      NativeWebRequest request,
      GcalClient gcalClient,
      BBHttpClientWrapper bbHttpClientWrapper,
      @Nullable UmamiAnalyticsService umamiAnalyticsService) {
    super(request);
    this.gcalClient = gcalClient;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.umamiAnalyticsService = umamiAnalyticsService;
  }

  @Override
  public ResponseEntity<Void> gcalCompleteOauth(String code, String state, String error) {
    if (error != null && !error.isBlank()) {
      trackOauth("error", null, Map.of("reason", "denied", "error", error));
      return errorResponse(SERVICE, "Google Calendar OAuth was not approved: " + error);
    }
    if (code == null || code.isBlank() || state == null || state.isBlank()) {
      trackOauth("error", null, Map.of("reason", "missing_params"));
      return errorResponse(SERVICE, "Missing required OAuth parameters. Please try again.");
    }
    if (!gcalClient.isConfigured()) {
      trackOauth("error", null, Map.of("reason", "not_configured"));
      return errorResponse(SERVICE, "Google Calendar is not configured.");
    }
    Optional<GcalClient.OauthState> oauthState = gcalClient.parseOauthState(state);
    if (oauthState.isEmpty()) {
      trackOauth("error", null, Map.of("reason", "invalid_state"));
      return errorResponse(SERVICE, "Invalid OAuth state. Please retry the linking flow.");
    }
    Optional<String> accountId =
        gcalClient.exchangeCode(oauthState.get().accountId(), oauthState.get().pendingKey(), code);
    if (accountId.isEmpty()) {
      trackOauth("error", oauthState.get().accountId(), Map.of("reason", "exchange_failed"));
      sendFollowup(
          bbHttpClientWrapper,
          oauthState.get().chatGuid(),
          oauthState.get().messageGuid(),
          "Calendar linking failed. Please try again.");
      return errorResponse(SERVICE, "OAuth failed. Please try again.");
    }
    trackOauth("success", oauthState.get().accountId(), Map.of());
    sendFollowup(
        bbHttpClientWrapper,
        oauthState.get().chatGuid(),
        oauthState.get().messageGuid(),
        "Calendar successfully linked.");
    return successResponse(SERVICE, "Google Calendar linked. You can close this tab.");
  }

  private void trackOauth(String status, String accountId, Map<String, ?> data) {
    if (umamiAnalyticsService == null) {
      return;
    }
    java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("service", SERVICE);
    payload.put("status", status);
    payload.putAll(data);
    umamiAnalyticsService.track("oauth_completion", "/server/oauth/gcal", accountId, payload);
  }
}

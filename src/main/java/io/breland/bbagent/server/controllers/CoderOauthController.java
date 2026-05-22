package io.breland.bbagent.server.controllers;

import static io.breland.bbagent.server.controllers.OauthControllerSupport.errorResponse;
import static io.breland.bbagent.server.controllers.OauthControllerSupport.sendFollowup;
import static io.breland.bbagent.server.controllers.OauthControllerSupport.successResponse;

import io.breland.bbagent.generated.api.CoderApiController;
import io.breland.bbagent.server.agent.tools.coder.CoderMcpClient;
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
public class CoderOauthController extends CoderApiController {
  private static final String SERVICE = "coder";

  private final CoderMcpClient coderMcpClient;
  private final BBHttpClientWrapper bbHttpClientWrapper;
  private final UmamiAnalyticsService umamiAnalyticsService;

  public CoderOauthController(
      NativeWebRequest request,
      CoderMcpClient coderMcpClient,
      BBHttpClientWrapper bbHttpClientWrapper,
      @Nullable UmamiAnalyticsService umamiAnalyticsService) {
    super(request);
    this.coderMcpClient = coderMcpClient;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.umamiAnalyticsService = umamiAnalyticsService;
  }

  @Override
  public ResponseEntity<Void> coderCompleteOauth(String code, String state, String error) {
    if (error != null && !error.isBlank()) {
      trackOauth("error", null, Map.of("reason", "denied", "error", error));
      return errorResponse(SERVICE, "Coder OAuth was not approved: " + error);
    }
    if (code == null || code.isBlank() || state == null || state.isBlank()) {
      trackOauth("error", null, Map.of("reason", "missing_params"));
      return errorResponse(SERVICE, "Missing required OAuth parameters. Please try again.");
    }
    if (!coderMcpClient.isConfigured()) {
      trackOauth("error", null, Map.of("reason", "not_configured"));
      return errorResponse(SERVICE, "Coder MCP is not configured.");
    }
    Optional<CoderMcpClient.OauthCompletion> completion = coderMcpClient.completeOauth(code, state);
    if (completion.isEmpty()) {
      trackOauth("error", null, Map.of("reason", "exchange_failed"));
      return errorResponse(SERVICE, "OAuth failed. Please try again.");
    }
    trackOauth("success", completion.get().accountId(), Map.of());
    sendFollowup(
        bbHttpClientWrapper,
        completion.get().chatGuid(),
        completion.get().messageGuid(),
        "Coder successfully linked.");
    return successResponse(SERVICE, "Coder linked. You can close this tab.");
  }

  private void trackOauth(String status, String accountId, Map<String, ?> data) {
    if (umamiAnalyticsService == null) {
      return;
    }
    java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("service", SERVICE);
    payload.put("status", status);
    payload.putAll(data);
    umamiAnalyticsService.track("oauth_completion", "/server/oauth/coder", accountId, payload);
  }
}

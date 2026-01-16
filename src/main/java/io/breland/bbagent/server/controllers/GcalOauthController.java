package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.api.GcalApiController;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class GcalOauthController extends GcalApiController {
  private final GcalClient gcalClient;
  private final BBHttpClientWrapper bbHttpClientWrapper;

  public GcalOauthController(
      NativeWebRequest request, GcalClient gcalClient, BBHttpClientWrapper bbHttpClientWrapper) {
    super(request);
    this.gcalClient = gcalClient;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
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
    boolean success = gcalClient.exchangeCode(oauthState.get().accountKey(), code);
    if (!success) {
      sendFollowup(
          oauthState.get().chatGuid(),
          oauthState.get().messageGuid(),
          "Calendar linking failed. Please try again.");
      return htmlResponse(HttpStatus.INTERNAL_SERVER_ERROR, "OAuth failed. Please try again.");
    }
    sendFollowup(
        oauthState.get().chatGuid(),
        oauthState.get().messageGuid(),
        "Calendar successfully linked.");
    return htmlResponse(HttpStatus.OK, "Google Calendar linked. You can close this tab.");
  }

  private void sendFollowup(String chatGuid, String messageGuid, String message) {
    if (chatGuid == null || chatGuid.isBlank() || message == null || message.isBlank()) {
      return;
    }
    ApiV1MessageTextPostRequest request = new ApiV1MessageTextPostRequest();
    request.setChatGuid(chatGuid);
    request.setMessage(message);
    if (messageGuid != null && !messageGuid.isBlank()) {
      request.setSelectedMessageGuid(messageGuid);
      request.setPartIndex(0);
    }
    bbHttpClientWrapper.sendTextDirect(request);
  }

  private ResponseEntity<String> htmlResponse(HttpStatus status, String message) {
    String body =
        "<!doctype html>"
            + "<html lang=\"en\">"
            + "<head>"
            + "<meta charset=\"utf-8\"/>"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
            + "<title>Google Calendar OAuth</title>"
            + "</head>"
            + "<body>"
            + "<p>"
            + escapeHtml(message)
            + "</p>"
            + "</body>"
            + "</html>";
    return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body);
  }

  private String escapeHtml(String input) {
    if (input == null) {
      return "";
    }
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}

package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

final class OauthControllerSupport {
  private static final String RESULT_PATH = "/oauth/callback";
  private static final String STATUS_ERROR = "error";
  private static final String STATUS_SUCCESS = "success";

  private OauthControllerSupport() {}

  static void sendFollowup(
      BBHttpClientWrapper bbHttpClientWrapper,
      String chatGuid,
      String messageGuid,
      String message) {
    if (isBlank(chatGuid) || isBlank(message)) {
      return;
    }
    ApiV1MessageTextPostRequest request = new ApiV1MessageTextPostRequest();
    request.setChatGuid(chatGuid);
    request.setMessage(message);
    if (!isBlank(messageGuid)) {
      request.setSelectedMessageGuid(messageGuid);
      request.setPartIndex(0);
    }
    bbHttpClientWrapper.sendTextDirect(request);
  }

  static ResponseEntity<Void> successResponse(String service, String message) {
    return redirectResponse(service, STATUS_SUCCESS, message);
  }

  static ResponseEntity<Void> errorResponse(String service, String message) {
    return redirectResponse(service, STATUS_ERROR, message);
  }

  private static ResponseEntity<Void> redirectResponse(
      String service, String status, String message) {
    URI location =
        UriComponentsBuilder.fromPath(RESULT_PATH)
            .queryParam("service", service)
            .queryParam("status", status)
            .queryParam("message", message)
            .build()
            .encode()
            .toUri();
    return ResponseEntity.status(HttpStatus.SEE_OTHER).location(location).build();
  }

  private static boolean isBlank(String input) {
    return input == null || input.isBlank();
  }
}

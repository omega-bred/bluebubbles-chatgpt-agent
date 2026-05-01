package io.breland.bbagent.server.controllers;

import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.commons.lang3.StringUtils.isAnyBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;

final class OauthCallbackSupport {
  private final BBHttpClientWrapper bbHttpClientWrapper;

  OauthCallbackSupport(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  void sendFollowup(String chatGuid, String messageGuid, String message) {
    if (isAnyBlank(chatGuid, message)) {
      return;
    }
    ApiV1MessageTextPostRequest request = new ApiV1MessageTextPostRequest();
    request.setChatGuid(chatGuid);
    request.setMessage(message);
    if (isNotBlank(messageGuid)) {
      request.setSelectedMessageGuid(messageGuid);
      request.setPartIndex(0);
    }
    bbHttpClientWrapper.sendTextDirect(request);
  }

  ResponseEntity<String> htmlResponse(HttpStatus status, String title, String message) {
    String body =
        "<!doctype html>"
            + "<html lang=\"en\">"
            + "<head>"
            + "<meta charset=\"utf-8\"/>"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
            + "<title>"
            + HtmlUtils.htmlEscape(defaultString(title))
            + "</title>"
            + "</head>"
            + "<body>"
            + "<p>"
            + HtmlUtils.htmlEscape(defaultString(message))
            + "</p>"
            + "</body>"
            + "</html>";
    return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body);
  }
}

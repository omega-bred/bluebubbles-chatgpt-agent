package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
class OauthCallbackSupport {
  private final BBHttpClientWrapper bbHttpClientWrapper;

  OauthCallbackSupport(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  void sendFollowup(String chatGuid, String messageGuid, String message) {
    if (StringUtils.isAnyBlank(chatGuid, message)) {
      return;
    }
    ApiV1MessageTextPostRequest request = new ApiV1MessageTextPostRequest();
    request.setChatGuid(chatGuid);
    request.setMessage(message);
    if (StringUtils.isNotBlank(messageGuid)) {
      request.setSelectedMessageGuid(messageGuid);
      request.setPartIndex(0);
    }
    bbHttpClientWrapper.sendTextDirect(request);
  }

  ResponseEntity<String> htmlResponse(String title, HttpStatus status, String message) {
    String body =
        "<!doctype html>"
            + "<html lang=\"en\">"
            + "<head>"
            + "<meta charset=\"utf-8\"/>"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
            + "<title>"
            + HtmlUtils.htmlEscape(StringUtils.defaultString(title))
            + "</title>"
            + "</head>"
            + "<body>"
            + "<p>"
            + HtmlUtils.htmlEscape(StringUtils.defaultString(message))
            + "</p>"
            + "</body>"
            + "</html>";
    return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body);
  }
}

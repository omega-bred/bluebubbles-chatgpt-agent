package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

@Component
public class OauthCallbackSupport {
  private final BBHttpClientWrapper bbHttpClientWrapper;

  public OauthCallbackSupport(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  public void sendFollowup(String chatGuid, String messageGuid, String message) {
    if (!StringUtils.hasText(chatGuid) || !StringUtils.hasText(message)) {
      return;
    }
    ApiV1MessageTextPostRequest request = new ApiV1MessageTextPostRequest();
    request.setChatGuid(chatGuid);
    request.setMessage(message);
    if (StringUtils.hasText(messageGuid)) {
      request.setSelectedMessageGuid(messageGuid);
      request.setPartIndex(0);
    }
    bbHttpClientWrapper.sendTextDirect(request);
  }

  public ResponseEntity<String> htmlResponse(String title, HttpStatus status, String message) {
    String body =
        "<!doctype html>"
            + "<html lang=\"en\">"
            + "<head>"
            + "<meta charset=\"utf-8\"/>"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
            + "<title>"
            + html(title)
            + "</title>"
            + "</head>"
            + "<body>"
            + "<p>"
            + html(message)
            + "</p>"
            + "</body>"
            + "</html>";
    return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body);
  }

  private String html(String input) {
    return HtmlUtils.htmlEscape(input == null ? "" : input);
  }
}

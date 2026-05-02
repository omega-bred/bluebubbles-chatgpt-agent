package io.breland.bbagent.server.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class OauthCallbackSupportTest {

  private final BBHttpClientWrapper bbHttpClientWrapper = Mockito.mock(BBHttpClientWrapper.class);
  private final OauthCallbackSupport support = new OauthCallbackSupport(bbHttpClientWrapper);

  @Test
  void sendFollowupTargetsSelectedMessageWhenMessageGuidIsPresent() {
    support.sendFollowup("chat-1", "message-1", "Calendar linked.");

    ArgumentCaptor<ApiV1MessageTextPostRequest> captor =
        ArgumentCaptor.forClass(ApiV1MessageTextPostRequest.class);
    verify(bbHttpClientWrapper).sendTextDirect(captor.capture());
    ApiV1MessageTextPostRequest request = captor.getValue();
    assertEquals("chat-1", request.getChatGuid());
    assertEquals("Calendar linked.", request.getMessage());
    assertEquals("message-1", request.getSelectedMessageGuid());
    assertEquals(0, request.getPartIndex());
  }

  @Test
  void sendFollowupSkipsBlankChatOrMessage() {
    support.sendFollowup(" ", "message-1", "Calendar linked.");
    support.sendFollowup("chat-1", "message-1", "");

    verifyNoInteractions(bbHttpClientWrapper);
  }

  @Test
  void htmlResponseEscapesTitleAndMessage() {
    var response =
        support.htmlResponse("OAuth <title>", HttpStatus.BAD_REQUEST, "<script>alert(1)</script>");

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals(MediaType.TEXT_HTML, response.getHeaders().getContentType());
    String body = response.getBody();
    assertTrue(body.contains("OAuth &lt;title&gt;"));
    assertTrue(body.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
    assertFalse(body.contains("<script>"));
  }
}

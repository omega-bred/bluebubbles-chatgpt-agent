package io.breland.bbagent.server.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class OauthCallbackSupportTest {

  @Test
  void htmlResponseEscapesTitleAndMessage() {
    OauthCallbackSupport support =
        new OauthCallbackSupport(Mockito.mock(BBHttpClientWrapper.class));

    var response =
        support.htmlResponse("OAuth <Title>", HttpStatus.BAD_REQUEST, "Linked <ok> & \"done\"");

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals(MediaType.TEXT_HTML, response.getHeaders().getContentType());
    assertTrue(response.getBody().contains("OAuth &lt;Title&gt;"));
    assertTrue(response.getBody().contains("Linked &lt;ok&gt; &amp; &quot;done&quot;"));
    assertFalse(response.getBody().contains("Linked <ok>"));
  }

  @Test
  void sendFollowupBuildsThreadReplyWhenMessageGuidIsPresent() {
    BBHttpClientWrapper client = Mockito.mock(BBHttpClientWrapper.class);
    OauthCallbackSupport support = new OauthCallbackSupport(client);
    ArgumentCaptor<ApiV1MessageTextPostRequest> requestCaptor =
        ArgumentCaptor.forClass(ApiV1MessageTextPostRequest.class);

    support.sendFollowup("chat-guid", "message-guid", "Linked.");

    verify(client).sendTextDirect(requestCaptor.capture());
    ApiV1MessageTextPostRequest request = requestCaptor.getValue();
    assertEquals("chat-guid", request.getChatGuid());
    assertEquals("Linked.", request.getMessage());
    assertEquals("message-guid", request.getSelectedMessageGuid());
    assertEquals(0, request.getPartIndex());
  }

  @Test
  void sendFollowupSkipsBlankChatOrMessage() {
    BBHttpClientWrapper client = Mockito.mock(BBHttpClientWrapper.class);
    OauthCallbackSupport support = new OauthCallbackSupport(client);

    support.sendFollowup(" ", "message-guid", "Linked.");
    support.sendFollowup("chat-guid", "message-guid", " ");

    verify(client, never()).sendTextDirect(Mockito.any(ApiV1MessageTextPostRequest.class));
  }
}

package io.breland.bbagent.server.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.twiliorcs.TwilioRcsClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class TwilioRcsWebhookControllerTest {

  @Test
  void rejectsInvalidTwilioSignature() {
    BBMessageAgent messageAgent = Mockito.mock(BBMessageAgent.class);
    TwilioRcsClient twilioRcsClient = Mockito.mock(TwilioRcsClient.class);
    when(twilioRcsClient.isValidWebhook(any(), anyMap(), any())).thenReturn(false);
    TwilioRcsWebhookController controller =
        new TwilioRcsWebhookController(null, messageAgent, twilioRcsClient);

    var response =
        controller.twilioRcsReceiveMessages(
            "SM123",
            "rcs:+15551234567",
            "rcs:brand_test_agent",
            "bad-signature",
            null,
            null,
            null,
            "hello",
            "0",
            "rcs",
            null);

    assertEquals(401, response.getStatusCode().value());
    verify(messageAgent, never()).handleIncomingMessage(Mockito.any());
  }

  @Test
  void mapsTwilioFormPayloadToIncomingMessage() {
    BBMessageAgent messageAgent = Mockito.mock(BBMessageAgent.class);
    TwilioRcsClient twilioRcsClient = Mockito.mock(TwilioRcsClient.class);
    when(twilioRcsClient.isValidWebhook(anyString(), anyMap(), anyString())).thenReturn(true);
    when(twilioRcsClient.emptyMessagingResponse()).thenReturn("<Response/>");

    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/twilioRcs/receive.messages");
    request.setServerName("bluechat.bre.land");
    request.setScheme("https");
    request.setParameter("MessageSid", "SM123");
    request.setParameter("From", "rcs:+15551234567");
    request.setParameter("To", "rcs:brand_test_agent");
    request.setParameter("Body", "hello over rcs");
    request.setParameter("NumMedia", "1");
    request.setParameter("MediaSid0", "ME123");
    request.setParameter("MediaUrl0", "https://api.twilio.com/media/ME123");
    request.setParameter("MediaContentType0", "image/png");
    TwilioRcsWebhookController controller =
        new TwilioRcsWebhookController(
            new ServletWebRequest(request), messageAgent, twilioRcsClient);

    var response =
        controller.twilioRcsReceiveMessages(
            "SM123",
            "rcs:+15551234567",
            "rcs:brand_test_agent",
            "valid-signature",
            null,
            "AC123",
            "MG123",
            "hello over rcs",
            "1",
            "rcs",
            null);

    assertEquals(200, response.getStatusCode().value());
    assertEquals("<Response/>", response.getBody());
    ArgumentCaptor<IncomingMessage> captor = ArgumentCaptor.forClass(IncomingMessage.class);
    verify(messageAgent).handleIncomingMessage(captor.capture());
    IncomingMessage message = captor.getValue();
    assertEquals(IncomingMessage.TRANSPORT_TWILIO_RCS, message.transport());
    assertEquals("twilio_rcs:+15551234567", message.chatGuid());
    assertEquals("+15551234567", message.sender());
    assertEquals("SM123", message.messageGuid());
    assertEquals("hello over rcs", message.text());
    assertFalse(message.isGroup());
    assertEquals(1, message.attachments().size());
    assertEquals("ME123", message.attachments().getFirst().guid());
    assertEquals("image/png", message.attachments().getFirst().mimeType());
  }
}

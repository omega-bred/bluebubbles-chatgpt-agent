package io.breland.bbagent.server.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.breland.bbagent.generated.model.LxmfMessageReceivedRequest;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class LxmfWebhookControllerTest {

  @Test
  void rejectsInvalidBridgeSecret() {
    BBMessageAgent messageAgent = Mockito.mock(BBMessageAgent.class);
    LxmfWebhookController controller = new LxmfWebhookController(null, messageAgent, "secret");

    var response =
        controller.lxmfReceiveMessages(
            new LxmfMessageReceivedRequest("msg-1", "AABB", "hello"), "wrong");

    assertEquals(401, response.getStatusCode().value());
    verify(messageAgent, never()).handleIncomingMessage(Mockito.any());
  }

  @Test
  void mapsLxmfPayloadToIncomingMessage() {
    BBMessageAgent messageAgent = Mockito.mock(BBMessageAgent.class);
    LxmfWebhookController controller = new LxmfWebhookController(null, messageAgent, "secret");
    LxmfMessageReceivedRequest request = new LxmfMessageReceivedRequest("msg-1", "AABB", "hello");
    request.setDestinationHash("ccdd");
    request.setTimestamp(OffsetDateTime.parse("2026-04-28T01:02:03Z"));

    var response = controller.lxmfReceiveMessages(request, "secret");

    assertEquals(200, response.getStatusCode().value());
    ArgumentCaptor<IncomingMessage> captor = ArgumentCaptor.forClass(IncomingMessage.class);
    verify(messageAgent).handleIncomingMessage(captor.capture());
    IncomingMessage message = captor.getValue();
    assertEquals(IncomingMessage.TRANSPORT_LXMF, message.transport());
    assertEquals("lxmf:aabb", message.chatGuid());
    assertEquals("aabb", message.sender());
    assertEquals("msg-1", message.messageGuid());
    assertEquals("hello", message.text());
    assertFalse(message.isGroup());
  }
}

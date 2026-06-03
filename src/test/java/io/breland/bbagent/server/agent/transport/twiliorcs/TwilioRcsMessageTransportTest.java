package io.breland.bbagent.server.agent.transport.twiliorcs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TwilioRcsMessageTransportTest {

  @Test
  void sendsTextToCurrentRcsSender() {
    TwilioRcsClient client = Mockito.mock(TwilioRcsClient.class);
    when(client.sendText("+15551234567", "hello")).thenReturn(true);
    TwilioRcsMessageTransport transport = new TwilioRcsMessageTransport(client);
    IncomingMessage message =
        new IncomingMessage(
            IncomingMessage.TRANSPORT_TWILIO_RCS,
            "twilio_rcs:+15551234567",
            "SM123",
            null,
            "incoming",
            false,
            "Twilio RCS",
            "+15551234567",
            false,
            Instant.now(),
            List.of(),
            false);

    assertTrue(transport.sendText(message, OutgoingTextMessage.plain("hello")));
    verify(client).sendText("+15551234567", "hello");
  }

  @Test
  void fallsBackToChatGuidWhenSenderMissing() {
    TwilioRcsClient client = Mockito.mock(TwilioRcsClient.class);
    when(client.sendText("+15551234567", "hello")).thenReturn(true);
    TwilioRcsMessageTransport transport = new TwilioRcsMessageTransport(client);
    IncomingMessage message =
        new IncomingMessage(
            IncomingMessage.TRANSPORT_TWILIO_RCS,
            "twilio_rcs:+15551234567",
            "SM123",
            null,
            "incoming",
            false,
            "Twilio RCS",
            null,
            false,
            Instant.now(),
            List.of(),
            false);

    assertTrue(transport.sendText(message, OutgoingTextMessage.plain("hello")));
    verify(client).sendText("+15551234567", "hello");
  }

  @Test
  void rejectsMissingMessage() {
    TwilioRcsMessageTransport transport =
        new TwilioRcsMessageTransport(Mockito.mock(TwilioRcsClient.class));

    assertFalse(transport.sendText(null, OutgoingTextMessage.plain("hello")));
  }
}

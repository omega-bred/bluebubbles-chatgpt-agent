package io.breland.bbagent.server.agent.transport.twiliorcs;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.OutgoingTextMessage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class TwilioRcsMessageTransport implements MessageTransport {
  private final TwilioRcsClient twilioRcsClient;

  public TwilioRcsMessageTransport(TwilioRcsClient twilioRcsClient) {
    this.twilioRcsClient = twilioRcsClient;
  }

  @Override
  public String id() {
    return IncomingMessage.TRANSPORT_TWILIO_RCS;
  }

  @Override
  public String displayName() {
    return "Twilio RCS";
  }

  @Override
  public boolean sendText(IncomingMessage message, OutgoingTextMessage outgoingMessage) {
    if (message == null || outgoingMessage == null || outgoingMessage.text() == null) {
      return false;
    }
    String recipient =
        StringUtils.firstNonBlank(
            message.sender(), IncomingMessage.stripTransportPrefix(message.chatGuid()));
    return twilioRcsClient.sendText(recipient, outgoingMessage.text());
  }
}

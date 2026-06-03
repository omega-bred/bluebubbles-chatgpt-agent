package io.breland.bbagent.server.agent.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.server.agent.IncomingMessage;
import org.junit.jupiter.api.Test;

class AgentAccountIdentifiersTest {

  @Test
  void normalizesImessagePhoneToE164LikeValue() {
    var normalized =
        AgentAccountIdentifiers.normalizeMessageIdentity(
                IncomingMessage.TRANSPORT_BLUEBUBBLES, "(555) 123-4567")
            .orElseThrow();

    assertEquals(AgentAccountIdentifiers.IMESSAGE_PHONE, normalized.type());
    assertEquals("+15551234567", normalized.value());
  }

  @Test
  void normalizesImessageEmailLowercase() {
    var normalized =
        AgentAccountIdentifiers.normalizeMessageIdentity(
                IncomingMessage.TRANSPORT_BLUEBUBBLES, "Mailto:Alice@Example.COM ")
            .orElseThrow();

    assertEquals(AgentAccountIdentifiers.IMESSAGE_EMAIL, normalized.type());
    assertEquals("alice@example.com", normalized.value());
  }

  @Test
  void normalizesLxmfAddressSeparatelyFromImessageHandles() {
    var normalized =
        AgentAccountIdentifiers.normalizeMessageIdentity(IncomingMessage.TRANSPORT_LXMF, " ABC123 ")
            .orElseThrow();

    assertEquals(AgentAccountIdentifiers.LXMF_ADDRESS, normalized.type());
    assertEquals("abc123", normalized.value());
    assertTrue(AgentAccountIdentifiers.equivalent("+1 555 123 4567", "5551234567"));
  }

  @Test
  void normalizesTwilioRcsPhoneSeparatelyFromImessagePhones() {
    var normalized =
        AgentAccountIdentifiers.normalizeMessageIdentity(
                IncomingMessage.TRANSPORT_TWILIO_RCS, "rcs:+1 (555) 123-4567")
            .orElseThrow();

    assertEquals(AgentAccountIdentifiers.TWILIO_RCS_PHONE, normalized.type());
    assertEquals("+15551234567", normalized.value());
  }
}

package io.breland.bbagent.server.agent.transport.twiliorcs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TwilioRcsAddressTest {

  @Test
  void normalizesRcsChannelEndpoints() {
    assertEquals("+15551234567", TwilioRcsAddress.normalizeEndpoint("rcs:+15551234567"));
    assertEquals("+15551234567", TwilioRcsAddress.normalizeEndpoint("tel:+15551234567"));
    assertEquals("rcs:+15551234567", TwilioRcsAddress.toRcsRecipient("+15551234567"));
    assertEquals("rcs:brand_test_agent", TwilioRcsAddress.toRcsSender("brand_test_agent"));
  }
}

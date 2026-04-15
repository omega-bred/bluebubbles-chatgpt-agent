package io.breland.bbagent.server.agent.tools.coder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CoderOauthStateCodecTest {

  @Test
  void roundTripsSignedState() {
    CoderOauthStateCodec codec = new CoderOauthStateCodec("test-secret", Duration.ofMinutes(10));

    String state =
        codec.createState("sender", "pending-id", "chat-guid", "message-guid").orElseThrow();
    Optional<CoderOauthStateCodec.OauthState> parsed = codec.parseState(state);

    assertTrue(parsed.isPresent());
    assertEquals("sender", parsed.get().accountBase());
    assertEquals("pending-id", parsed.get().pendingId());
    assertEquals("chat-guid", parsed.get().chatGuid());
    assertEquals("message-guid", parsed.get().messageGuid());
  }

  @Test
  void rejectsWrongSecret() {
    CoderOauthStateCodec signer = new CoderOauthStateCodec("test-secret", Duration.ofMinutes(10));
    CoderOauthStateCodec verifier =
        new CoderOauthStateCodec("other-secret", Duration.ofMinutes(10));
    String state = signer.createState("sender", "pending-id", null, null).orElseThrow();

    assertTrue(verifier.parseState(state).isEmpty());
  }
}

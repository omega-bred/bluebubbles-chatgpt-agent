package io.breland.bbagent.server.agent.transport.bb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import org.junit.jupiter.api.Test;

class BlueBubblesMessageHandleCompatibilityTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void decodesScalarMessageHandleFromWireJson() throws Exception {
    Message message =
        mapper.readValue(
            """
            {"guid":"8d7d7d09-dd10-425f-9b72-8ef322eca49d","handle":"+15555550123"}
            """,
            Message.class);

    assertThat(decodedHandleAddress(message)).isEqualTo("+15555550123");
  }

  @Test
  void decodesObjectMessageHandleFromWireJson() throws Exception {
    Message message =
        mapper.readValue(
            """
            {"guid":"8d7d7d09-dd10-425f-9b72-8ef322eca49d","handle":{"address":"alice@example.com"}}
            """,
            Message.class);

    assertThat(decodedHandleAddress(message)).isEqualTo("alice@example.com");
  }

  private String decodedHandleAddress(Message message) {
    return BlueBubblesHandleAddress.from(message.getHandle());
  }
}

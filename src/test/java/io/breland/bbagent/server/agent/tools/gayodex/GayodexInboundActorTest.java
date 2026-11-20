package io.breland.bbagent.server.agent.tools.gayodex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GayodexInboundActorTest {
  static final String PHONE = "+12025550101";
  static final String OTHER = "+12025550102";
  static final String GROUP = "iMessage;+;group";
  static final String GUID = "3b8b65ef-7187-4c02-a651-8fa2ca353229";
  static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  static GayodexProperties configured(String url) {
    var p = new GayodexProperties();
    p.setEnabled(true);
    p.setBaseUrl(url);
    p.setToken("synthetic-test-credential-" + "x".repeat(43));
    return p;
  }

  static IncomingMessage incoming(String phone) {
    return new IncomingMessage(
        "bluebubbles",
        GROUP,
        GUID,
        null,
        "Save my bio",
        false,
        "iMessage",
        phone,
        true,
        Instant.ofEpochMilli(1000),
        List.of(),
        false);
  }

  static IncomingMessage verified(String phone) {
    return incoming(phone).withVerifiedBlueBubblesInbound(List.of());
  }

  @Test
  void emailMissingMalformedAndUnsupportedActorsCannotBorrowAccountPhoneAliases() {
    for (String sender :
        new String[] {
          "person@example.test",
          "call +12025550101",
          "+12025550101@evil.test",
          "+00012345",
          "",
          null
        }) assertTrue(GayodexInboundActor.verifiedPhone(verified(sender)).isEmpty());
    for (String transport : new String[] {"lxmf", "unknown", "", null}) {
      var m =
          new IncomingMessage(
              transport,
              GROUP,
              GUID,
              null,
              "text",
              false,
              "iMessage",
              PHONE,
              true,
              Instant.EPOCH,
              List.of(),
              false);
      assertTrue(
          GayodexInboundActor.verifiedPhone(m.withVerifiedBlueBubblesInbound(List.of())).isEmpty());
    }
    for (Boolean fromMe : new Boolean[] {true, null}) {
      var m =
          new IncomingMessage(
              "bluebubbles",
              GROUP,
              GUID,
              null,
              "text",
              fromMe,
              "iMessage",
              PHONE,
              true,
              Instant.EPOCH,
              List.of(),
              false);
      assertTrue(
          GayodexInboundActor.verifiedPhone(m.withVerifiedBlueBubblesInbound(List.of())).isEmpty());
    }
    var scheduled =
        new IncomingMessage(
            GROUP,
            "scheduled-" + UUID.randomUUID(),
            null,
            "Save bio",
            false,
            "iMessage",
            PHONE,
            true,
            Instant.now(),
            List.of(),
            true);
    assertTrue(
        GayodexInboundActor.verifiedPhone(scheduled.withVerifiedBlueBubblesInbound(List.of()))
            .isEmpty());
    assertTrue(GayodexInboundActor.verifiedPhone(incoming(PHONE)).isEmpty());
  }

  @Test
  void provenanceSurvivesWorkflowJsonButLegacyAndHistoryMessagesDefaultToUnverified()
      throws Exception {
    var verified = verified(PHONE);
    var converter =
        new io.breland.bbagent.server.agent.cadence.CadenceWorkflowConfig()
            .cadenceWorkflowClient(
                mock(com.uber.cadence.serviceclient.WorkflowServiceGrpc.class),
                new io.breland.bbagent.server.agent.AgentWorkflowProperties(),
                new io.breland.bbagent.server.blobstore.InMemoryBlobStore())
            .getOptions()
            .getDataConverter();
    assertTrue(
        converter
            .fromData(converter.toData(verified), IncomingMessage.class, IncomingMessage.class)
            .verifiedBlueBubblesInbound());
    var old =
        MAPPER.readTree(
            new String(converter.toData(verified), java.nio.charset.StandardCharsets.UTF_8));
    ((com.fasterxml.jackson.databind.node.ObjectNode) old).remove("verifiedBlueBubblesInbound");
    assertFalse(
        converter
            .fromData(
                old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                IncomingMessage.class,
                IncomingMessage.class)
            .verifiedBlueBubblesInbound());
    var history =
        new io.breland.bbagent.generated.bluebubblesclient.model
            .ApiV1ChatChatGuidMessageGet200ResponseDataInner();
    assertFalse(IncomingMessage.create(history).verifiedBlueBubblesInbound());
  }
}

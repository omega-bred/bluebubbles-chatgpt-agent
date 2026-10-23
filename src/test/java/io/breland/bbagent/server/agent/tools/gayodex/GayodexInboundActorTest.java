package io.breland.bbagent.server.agent.tools.gayodex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GayodexInboundActorTest {
  static final String PHONE = "+12025550101";
  static final String OTHER = "+12025550102";
  static final String GROUP = "iMessage;+;group";
  static final String GUID = "3b8b65ef-7187-4c02-a651-8fa2ca353229";
  static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  final BBHttpClientWrapper bb = mock(BBHttpClientWrapper.class);
  final GayodexProperties properties = configured("http://localhost:8767");
  final GayodexInboundActor actors = new GayodexInboundActor(properties, bb, MAPPER);

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

  Message stored(String phone) {
    return new Message()
        .guid(UUID.fromString(GUID))
        .text("Save my bio")
        .isFromMe(false)
        .dateCreated(1000L)
        .handle(Map.of("address", phone, "service", "iMessage"))
        .chats(List.of(new Chat().guid(GROUP)))
        .attachments(List.of(Map.of("guid", "own-photo", "mimeType", "image/png")));
  }

  @Test
  void authenticatedTransportReplacesWebhookAttachmentsAndNormalizesOnlyActualSender() {
    when(bb.getMessage(GUID)).thenReturn(stored(PHONE));
    var incoming =
        incoming("+1 (202) 555-0101")
            .withAttachments(
                List.of(
                    new IncomingAttachment(
                        "other-photo",
                        "image/png",
                        "fake",
                        "https://example.invalid",
                        null,
                        "fake")));
    var result = actors.authenticate(incoming);
    assertEquals(PHONE, GayodexInboundActor.verifiedPhone(result).orElseThrow());
    assertEquals(
        List.of(new IncomingAttachment("own-photo", "image/png", null, null, null, null)),
        result.attachments());
    assertTrue(result.withText("starter code removed").verifiedBlueBubblesInbound());
    assertTrue(result.withThreadOriginatorGuid("thread").verifiedBlueBubblesInbound());
    assertFalse(result.withAttachments(List.of()).verifiedBlueBubblesInbound());
  }

  @Test
  void twoSendersInSameGroupKeepSeparatePhoneBindingsEvenWithIdenticalText() {
    when(bb.getMessage(GUID)).thenReturn(stored(PHONE), stored(OTHER));
    assertEquals(
        PHONE,
        GayodexInboundActor.verifiedPhone(actors.authenticate(incoming(PHONE))).orElseThrow());
    assertEquals(
        OTHER,
        GayodexInboundActor.verifiedPhone(actors.authenticate(incoming(OTHER))).orElseThrow());
  }

  @Test
  void spoofedSenderTextConversationTimestampAndOutboundRecordsFailClosed() {
    var incoming = incoming(PHONE);
    for (Message stored :
        List.of(
            stored(OTHER),
            stored(PHONE).text("different instruction"),
            stored(PHONE).dateCreated(2000L),
            stored(PHONE).isFromMe(true),
            stored(PHONE).isSystemMessage(true),
            stored(PHONE).isServiceMessage(true),
            stored(PHONE).handle(Map.of("address", PHONE, "service", "SMS")),
            stored(PHONE).guid(UUID.randomUUID()),
            stored(PHONE).chats(List.of(new Chat().guid("different"))))) {
      when(bb.getMessage(GUID)).thenReturn(stored);
      assertFalse(actors.authenticate(incoming).verifiedBlueBubblesInbound());
    }
    when(bb.getMessage(GUID)).thenThrow(new RuntimeException("private client detail"));
    assertFalse(actors.authenticate(incoming).verifiedBlueBubblesInbound());
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
        }) assertFalse(actors.authenticate(incoming(sender)).verifiedBlueBubblesInbound());
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
    assertFalse(actors.authenticate(scheduled).verifiedBlueBubblesInbound());
    assertTrue(GayodexInboundActor.verifiedPhone(incoming(PHONE)).isEmpty());
    verifyNoInteractions(bb);
  }

  @Test
  void disabledPartialOrUnsafeConfigurationDoesNotFetchTransportOrEnableDiscovery() {
    for (String url :
        new String[] {
          "",
          "https://user:pass@example.test",
          "https://example.test/path",
          "https://example.test/?q=1",
          "file:///tmp/x"
        }) {
      properties.setBaseUrl(url);
      assertFalse(properties.isConfigured());
      assertFalse(actors.authenticate(incoming(PHONE)).verifiedBlueBubblesInbound());
    }
    properties.setBaseUrl("http://localhost:8767");
    properties.setToken("");
    assertFalse(properties.isConfigured());
    properties.setToken("x".repeat(43));
    properties.setEnabled(false);
    assertFalse(actors.authenticate(incoming(PHONE)).verifiedBlueBubblesInbound());
    verifyNoInteractions(bb);
  }

  @Test
  void provenanceSurvivesWorkflowJsonButLegacyAndHistoryMessagesDefaultToUnverified()
      throws Exception {
    var verified = verified(PHONE);
    var converter =
        new io.breland.bbagent.server.agent.cadence.CadenceWorkflowConfig()
            .cadenceWorkflowClient(
                mock(com.uber.cadence.serviceclient.WorkflowServiceGrpc.class),
                new io.breland.bbagent.server.agent.AgentWorkflowProperties())
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

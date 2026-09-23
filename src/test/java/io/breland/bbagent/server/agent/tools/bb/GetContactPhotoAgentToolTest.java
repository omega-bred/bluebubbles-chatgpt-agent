package io.breland.bbagent.server.agent.tools.bb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.model.Contact;
import io.breland.bbagent.server.agent.AgentOutboundService;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.ToolContextFixture;
import io.breland.bbagent.server.agent.tools.wallart.WallartContactPhotos;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetContactPhotoAgentToolTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final BBHttpClientWrapper bb = mock(BBHttpClientWrapper.class);
  private final AgentOutboundService outbound = mock(AgentOutboundService.class);
  private final GetContactPhotoAgentTool provider =
      new GetContactPhotoAgentTool(bb, new WallartContactPhotos(bb));
  private ToolContext context;
  private byte[] bytes;

  @BeforeEach
  void setup() throws Exception {
    context =
        ToolContextFixture.with(message("bluebubbles", "alice@example.com"))
            .outboundService(outbound)
            .build();
    when(outbound.canSendResponses(any())).thenReturn(true);
    when(outbound.consumeMessageResponseQuota(any(), any())).thenReturn(true);
    when(bb.getConversationInfoJson("chat"))
        .thenReturn(
            mapper.readTree(
                "{\"participants\":[{\"address\":\"alice@example.com\"},{\"address\":\"bob@example.com\"}]}"));
    bytes = photo("png");
    saved(bytes);
  }

  @Test
  void loadsActualSenderImageWithoutSendingOrFetchingOtherParticipants() throws Exception {
    String output = run(false);
    assertTrue(output.contains("source=contacts"));
    assertTrue(output.contains("No photo was sent"));
    assertEquals(1, context.modelContent().size());
    assertEquals(
        "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes),
        context.modelContent().getFirst().asInputImage().imageUrl().orElseThrow());
    assertFalse(output.contains(Base64.getEncoder().encodeToString(bytes)));
    verify(bb, never()).getContactPhotosForAddress(eq("bob@example.com"), any());
    verify(bb, never()).getSharedContactPhoto(anyString(), any());
    verify(bb, never()).sendMultipartMessage(anyString(), any(), anyList());
    verify(outbound, never()).consumeMessageResponseQuota(any(), any());
  }

  @Test
  void omittedSendFlagOnlyViews() {
    String output = provider.getTool().handler().apply(context, mapper.createObjectNode());
    assertTrue(output.contains("No photo was sent"));
    assertEquals(1, context.modelContent().size());
    verify(bb, never()).sendMultipartMessage(anyString(), any(), anyList());
  }

  @Test
  void sendsExactImageIntoCurrentChatAndStillLoadsForModel() throws Exception {
    when(bb.sendMultipartMessage(eq("chat"), isNull(), anyList())).thenReturn(true);
    assertTrue(run(true).contains("Photo sent to this conversation"));
    verify(bb)
        .sendMultipartMessage(
            eq("chat"),
            isNull(),
            argThat(
                files ->
                    files.size() == 1
                        && files.getFirst().filename().equals("contact-photo.png")
                        && java.util.Arrays.equals(bytes, files.getFirst().bytes())));
    verify(outbound)
        .recordAssistantTurn(eq(context.message()), eq("[current sender contact photo]"), isNull());
    assertEquals(1, context.modelContent().size());
  }

  @Test
  void fallsBackToSharedProfileAndNormalizesTiff() throws Exception {
    when(bb.getContactPhotosForAddress(eq("alice@example.com"), any())).thenReturn(List.of());
    when(bb.getSharedContactPhoto(eq("alice@example.com"), any()))
        .thenReturn(
            mapper
                .createObjectNode()
                .put("avatar", Base64.getEncoder().encodeToString(photo("tiff"))));
    assertTrue(run(false).contains("source=shared_profile"));
    String url = context.modelContent().getFirst().asInputImage().imageUrl().orElseThrow();
    assertTrue(url.startsWith("data:image/png;base64,"));
    assertNotNull(
        ImageIO.read(
            new java.io.ByteArrayInputStream(Base64.getDecoder().decode(url.split(",", 2)[1]))));
  }

  @Test
  void missingAndUnsupportedPhotosAreNotLoadedOrSent() throws Exception {
    saved(new byte[] {1, 2, 3});
    assertTrue(run(true).contains("unsupported_photo"));
    when(bb.getContactPhotosForAddress(eq("alice@example.com"), any())).thenReturn(List.of());
    assertTrue(run(true).contains("missing_photo"));
    assertTrue(context.modelContent().isEmpty());
    verify(bb, never()).sendMultipartMessage(anyString(), any(), anyList());
  }

  @Test
  void rejectsUnverifiedSenderBeforeFetchingPhoto() {
    context =
        ToolContextFixture.with(message("bluebubbles", "outsider@example.com"))
            .outboundService(outbound)
            .build();
    assertTrue(run(true).startsWith("failed:"));
    verify(bb, never()).getContactPhotosForAddress(anyString(), any());
    verify(bb, never()).getSharedContactPhoto(anyString(), any());
    assertTrue(context.modelContent().isEmpty());
  }

  @Test
  void rejectsOtherTransportsAndOutdatedWork() {
    context =
        ToolContextFixture.with(message("lxmf", "alice@example.com"))
            .outboundService(outbound)
            .build();
    assertTrue(run(true).startsWith("unavailable:"));
    context =
        ToolContextFixture.with(message("bluebubbles", "alice@example.com"))
            .outboundService(outbound)
            .build();
    when(outbound.canSendResponses(any())).thenReturn(false);
    assertEquals("skipped: outdated workflow", run(true));
    verify(bb, never()).getConversationInfoJson(anyString());
  }

  @Test
  void rechecksWorkflowAfterFetching() {
    when(outbound.canSendResponses(any())).thenReturn(true, false);
    assertEquals("skipped: outdated workflow", run(true));
    assertTrue(context.modelContent().isEmpty());
    verify(bb, never()).sendMultipartMessage(anyString(), any(), anyList());
  }

  @Test
  void quotaDoesNotPreventViewingButPreventsDelivery() {
    when(outbound.consumeMessageResponseQuota(any(), any())).thenReturn(false);
    assertTrue(run(true).contains("Sending was rate limited"));
    assertEquals(1, context.modelContent().size());
    verify(bb, never()).sendMultipartMessage(anyString(), any(), anyList());
  }

  @Test
  void uncertainDeliveryKeepsImageAvailableAndDoesNotClaimSuccessOrLeakErrors() {
    when(bb.sendMultipartMessage(anyString(), any(), anyList()))
        .thenThrow(new IllegalStateException("password=secret"));
    String output = run(true);
    assertTrue(output.startsWith("Loaded"));
    assertTrue(output.contains("Delivery could not be confirmed"));
    assertFalse(output.contains("secret"));
    assertEquals(1, context.modelContent().size());
    verify(outbound, never()).recordAssistantTurn(any(), any(), any());
  }

  @Test
  void imageReachesModelThroughActivityRunnerEvenWhenDeliveryIsUnconfirmed() {
    var factory = mock(io.breland.bbagent.server.agent.tools.ToolContextFactory.class);
    when(factory.create(context.message(), null)).thenReturn(context);
    var registry = mock(io.breland.bbagent.server.agent.tools.AgentToolRegistry.class);
    var tool = provider.getTool();
    when(registry.resolveTool(tool.name(), context.message())).thenReturn(tool);
    var runner =
        new io.breland.bbagent.server.agent.AgentToolActivityRunner(
            mapper,
            factory,
            registry,
            mock(io.breland.bbagent.server.metrics.AgentMetricsService.class));
    var result =
        runner.run(
            com.openai.models.responses.ResponseFunctionToolCall.builder()
                .callId("photo-call")
                .name(tool.name())
                .arguments("{\"sendToChat\":true}")
                .build(),
            context.message(),
            null);
    var json =
        com.openai.core.JsonValue.from(result)
            .convert(com.fasterxml.jackson.databind.JsonNode.class);
    assertTrue(json.path("output").isArray());
    assertTrue(
        json.path("output")
            .get(0)
            .path("text")
            .asText()
            .contains("Delivery could not be confirmed"));
    assertEquals(
        "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes),
        json.path("output").get(1).path("image_url").asText());
  }

  private String run(boolean send) {
    return provider
        .getTool()
        .handler()
        .apply(context, mapper.createObjectNode().put("sendToChat", send));
  }

  private void saved(byte[] data) throws Exception {
    var contact =
        mapper.readTree(
            "{\"displayName\":\"Alice\",\"emails\":[{\"address\":\"alice@example.com\"}]}");
    ((com.fasterxml.jackson.databind.node.ObjectNode) contact)
        .put("avatar", Base64.getEncoder().encodeToString(data));
    when(bb.getContactPhotosForAddress(eq("alice@example.com"), any()))
        .thenReturn(List.of(mapper.convertValue(contact, Contact.class)));
  }

  private static IncomingMessage message(String transport, String sender) {
    return new IncomingMessage(
        transport,
        "chat",
        "incoming",
        null,
        "what is my contact photo?",
        false,
        "iMessage",
        sender,
        true,
        Instant.EPOCH,
        List.of(),
        false);
  }

  private static byte[] photo(String format) throws Exception {
    var out = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, out));
    return out.toByteArray();
  }
}

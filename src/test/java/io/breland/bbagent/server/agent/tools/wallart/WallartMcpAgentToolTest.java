package io.breland.bbagent.server.agent.tools.wallart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.AgentOutboundService;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.ToolContextFixture;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WallartMcpAgentToolTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final WallartMcpClient client = mock(WallartMcpClient.class);
  private final WallartConversationAccess access = mock(WallartConversationAccess.class);
  private final BBHttpClientWrapper bb = mock(BBHttpClientWrapper.class);
  private final AgentOutboundService outbound = mock(AgentOutboundService.class);
  private final WallartMcpAgentTool provider =
      new WallartMcpAgentTool(
          client,
          access,
          new WallartImageInputs(bb, client, mapper, new WallartContactPhotos(bb)),
          bb);

  @Test
  void submitsTrimmedPromptAndAllowsTournamentWithoutPrompt() throws Exception {
    var context = context(List.of());
    when(client.showNewArt("landscape")).thenReturn("submitted");
    when(client.showNewArt(null)).thenReturn("tournament");
    assertEquals("submitted", call("showNewArt", context, "{\"prompt\":\"  landscape  \"}"));
    assertEquals("tournament", call("showNewArt", context, "{}"));
    assertEquals("tournament", call("showNewArt", context, "{\"prompt\":\" \"}"));
  }

  @Test
  void deniesEveryToolBeforeFetchingImagesOrCallingMcp() throws Exception {
    var context = context(List.of());
    when(access.isAllowed(context.message())).thenReturn(false);
    for (String name : WallartMcpAgentTool.TOOL_NAMES) {
      assertEquals(
          "The wallart tool is not available in this conversation.", call(name, context, "{}"));
    }
    verifyNoInteractions(client, bb, outbound);
  }

  @Test
  void skipsOutdatedWorkBeforeAnyIo() throws Exception {
    var context = context(List.of());
    when(outbound.canSendResponses(null)).thenReturn(false);
    for (String name : WallartMcpAgentTool.TOOL_NAMES) {
      assertEquals("skipped: outdated workflow", call(name, context, "{}"));
    }
    verifyNoInteractions(client, bb);
  }

  @Test
  void sendsCurrentArtAsBytesWithoutReturningBase64ToModel() throws Exception {
    var context = context(List.of());
    String data = WallartImageInputsTest.png();
    when(client.getCurrentArt()).thenReturn(new McpSchema.ImageContent(null, data, "image/png"));
    when(bb.sendMultipartMessage(anyString(), isNull(), anyList())).thenReturn(true);
    String result = call("getCurrentArt", context, "{}");
    assertTrue(result.contains("photo sent"));
    assertFalse(result.contains(data));
    ArgumentCaptor<List<BBHttpClientWrapper.AttachmentData>> attachments =
        ArgumentCaptor.forClass(List.class);
    verify(bb)
        .sendMultipartMessage(eq(context.message().chatGuid()), isNull(), attachments.capture());
    assertArrayEquals(
        java.util.Base64.getDecoder().decode(data), attachments.getValue().getFirst().bytes());
    verify(outbound).recordAssistantTurn(context.message(), "[current wall art photo]", null);
  }

  @Test
  void checksQuotaAndDoesNotClaimFailedDelivery() throws Exception {
    var context = context(List.of());
    when(client.getCurrentArt())
        .thenReturn(new McpSchema.ImageContent(null, WallartImageInputsTest.png(), "image/png"));
    when(outbound.consumeMessageResponseQuota(context.message(), null)).thenReturn(false);
    assertEquals("rate limited", call("getCurrentArt", context, "{}"));
    verifyNoInteractions(bb);
    when(outbound.consumeMessageResponseQuota(context.message(), null)).thenReturn(true);
    assertTrue(call("getCurrentArt", context, "{}").startsWith("failed:"));
    verify(outbound, never()).recordAssistantTurn(any(), anyString(), any());
  }

  @Test
  void displaysAttachmentAndComposesCurrentSceneWithPhotoInRequestedOrder() throws Exception {
    String photo = WallartImageInputsTest.png();
    var context =
        context(List.of(new IncomingAttachment(null, "image/png", "photo.png", null, null, photo)));
    when(client.getCurrentArt()).thenReturn(new McpSchema.ImageContent(null, photo, "image/png"));
    when(client.showImage(anyMap())).thenReturn("submitted");
    when(client.composeArt(anyString(), anyList())).thenReturn("submitted");
    assertEquals(
        "submitted", call("showImage", context, "{\"image\":{\"source\":\"attachment\"}}"));
    assertEquals(
        "submitted",
        call(
            "composeArt",
            context,
            """
        {"prompt":"  add this person on the left  ","images":[
          {"source":"current_art","description":"base scene"},
          {"source":"attachment","index":1,"description":"person"}]}
        """));
    verify(client)
        .showImage(
            argThat(
                image ->
                    image.get("data").equals(photo) && image.get("mime_type").equals("image/png")));
    verify(client)
        .composeArt(
            eq("add this person on the left"),
            argThat(
                images ->
                    images.size() == 2
                        && images.get(0).get("description").equals("base scene")
                        && images.get(1).get("description").equals("person")));
  }

  @Test
  void checksStatusAndReportsAmbiguousSubmissionWithoutAutomaticRetry() throws Exception {
    var context = context(List.of());
    when(client.getArtStatus("workflow-1")).thenReturn("FAILED");
    assertEquals("FAILED", call("getArtStatus", context, "{\"workflowId\":\" workflow-1 \"}"));
    when(client.showNewArt(anyString())).thenThrow(new IllegalStateException("private response"));
    String result = call("showNewArt", context, "{\"prompt\":\"art\"}");
    assertTrue(result.startsWith("failed:"));
    assertTrue(result.contains("may have been accepted"));
    assertFalse(result.contains("private response"));
    verify(client).showNewArt("art");
  }

  @Test
  void validatesImageAndPromptBeforeSubmission() throws Exception {
    var context = context(List.of());
    assertTrue(call("composeArt", context, "{\"prompt\":\" \"}").startsWith("error:"));
    assertTrue(call("showImage", context, "{}").startsWith("error:"));
    assertTrue(
        call("showImage", context, "{\"image\":{\"source\":\"attachment\"}}").startsWith("error:"));
    verifyNoInteractions(client, bb);
  }

  @Test
  void listsContactMetadataAndRejectsMultiPersonDirectDisplay() throws Exception {
    var context = context(List.of());
    when(bb.getConversationInfoJson(context.message().chatGuid()))
        .thenReturn(
            mapper.readTree(
                """
      {"participants":[{"address":"alice@example.com"},{"address":"bob@example.com"}]}
      """));
    String data = WallartImageInputsTest.png();
    for (String address : List.of("alice@example.com", "bob@example.com")) {
      var contact =
          mapper.convertValue(
              java.util.Map.of(
                  "displayName",
                  address,
                  "avatar",
                  data,
                  "emails",
                  List.of(java.util.Map.of("address", address))),
              io.breland.bbagent.generated.bluebubblesclient.model.Contact.class);
      when(bb.getContactPhotosForAddress(eq(address), any())).thenReturn(List.of(contact));
    }
    String result = call("listWallartContactPhotos", context, "{}");
    assertEquals(2, mapper.readTree(result).path("participants").size());
    assertFalse(result.contains(data));
    assertTrue(
        call(
                "showImage",
                context,
                """
        {"image":{"source":"all_contact_photos"}}
        """)
            .contains("requires one photo"));
    verifyNoInteractions(client);
  }

  private ToolContext context(List<IncomingAttachment> attachments) {
    var message =
        new IncomingMessage(
            "iMessage;-;+18033861737",
            "message-guid",
            null,
            "art",
            false,
            "iMessage",
            "+18033861737",
            false,
            Instant.EPOCH,
            attachments,
            false);
    when(access.isAllowed(message)).thenReturn(true);
    when(outbound.canSendResponses(null)).thenReturn(true);
    when(outbound.consumeMessageResponseQuota(message, null)).thenReturn(true);
    return ToolContextFixture.with(message).outboundService(outbound).build();
  }

  private String call(String name, ToolContext context, String args) throws Exception {
    return provider.getTools().stream()
        .filter(tool -> tool.name().equals(name))
        .findFirst()
        .orElseThrow()
        .handler()
        .apply(context, mapper.readTree(args));
  }
}

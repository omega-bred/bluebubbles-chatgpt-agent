package io.breland.bbagent.server.agent.tools.bb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetGroupIconAgentToolTest {
  private final BBHttpClientWrapper bb = mock(BBHttpClientWrapper.class);
  private final ToolContext context = mock(ToolContext.class);
  private final GetGroupIconAgentTool provider = new GetGroupIconAgentTool(bb);

  @BeforeEach
  void setup() {
    when(context.message()).thenReturn(message("bluebubbles", "any;+;group", true));
    when(context.canSendResponses()).thenReturn(true);
    when(context.consumeMessageResponseQuota()).thenReturn(true);
  }

  @Test
  void sendsOriginalPhotoOnlyToCurrentGroup() throws Exception {
    byte[] image = photo("png");
    when(bb.getConversationIcon("any;+;group")).thenReturn(Optional.of(image));
    when(bb.sendMultipartMessage(eq("any;+;group"), isNull(), anyList())).thenReturn(true);
    assertTrue(run().startsWith("Current group icon photo sent"));
    verify(bb).getConversationIcon("any;+;group");
    verify(bb)
        .sendMultipartMessage(
            eq("any;+;group"),
            isNull(),
            argThat(
                files ->
                    files.size() == 1
                        && files.getFirst().filename().equals("group-icon.png")
                        && java.util.Arrays.equals(image, files.getFirst().bytes())));
    verify(context).consumeMessageResponseQuota();
    verify(context).recordAssistantTurn("[current group icon photo]");
    verifyNoMoreInteractions(bb);
  }

  @Test
  void normalizesMacTiffPhotoToPng() throws Exception {
    when(bb.getConversationIcon(anyString())).thenReturn(Optional.of(photo("tiff")));
    when(bb.sendMultipartMessage(anyString(), isNull(), anyList()))
        .thenAnswer(
            invocation -> {
              List<BBHttpClientWrapper.AttachmentData> files = invocation.getArgument(2);
              assertEquals("group-icon.png", files.getFirst().filename());
              assertNotNull(
                  ImageIO.read(new java.io.ByteArrayInputStream(files.getFirst().bytes())));
              return true;
            });
    assertTrue(run().startsWith("Current group icon photo sent"));
  }

  @Test
  void rejectsDirectChatsOtherTransportsAndMissingContextBeforeFetching() {
    for (var message :
        List.of(
            message("bluebubbles", "direct", false),
            message("lxmf", "group", true),
            message("bluebubbles", " ", true))) {
      when(context.message()).thenReturn(message);
      assertTrue(run().startsWith("unavailable:"));
    }
    when(context.message()).thenReturn(null);
    assertTrue(run().startsWith("unavailable:"));
    verifyNoInteractions(bb);
  }

  @Test
  void unavailablePhotoDoesNotConsumeQuotaOrSend() {
    when(bb.getConversationIcon(anyString())).thenReturn(Optional.empty());
    assertTrue(run().startsWith("unavailable:"));
    verify(context, never()).consumeMessageResponseQuota();
    verify(bb, never()).sendMultipartMessage(anyString(), any(), anyList());
  }

  @Test
  void rejectsUnreadablePhotosAndSanitizesRetrievalErrors() {
    when(bb.getConversationIcon(anyString())).thenReturn(Optional.of(new byte[] {1, 2, 3}));
    assertTrue(run().startsWith("failed:"));
    when(bb.getConversationIcon(anyString()))
        .thenThrow(new IllegalStateException("password=secret"));
    assertFalse(run().contains("secret"));
    verify(context, never()).consumeMessageResponseQuota();
    verify(bb, never()).sendMultipartMessage(anyString(), any(), anyList());
  }

  @Test
  void stopsOutdatedWorkBeforeFetchAndRechecksBeforeDelivery() throws Exception {
    when(context.canSendResponses()).thenReturn(false);
    assertEquals("skipped: outdated workflow", run());
    verifyNoInteractions(bb);
    when(context.canSendResponses()).thenReturn(true, false);
    when(bb.getConversationIcon(anyString())).thenReturn(Optional.of(photo("png")));
    assertEquals("skipped: outdated workflow", run());
    verify(context, never()).consumeMessageResponseQuota();
    verify(bb, never()).sendMultipartMessage(anyString(), any(), anyList());
  }

  @Test
  void respectsQuotaAndDoesNotClaimUnconfirmedDelivery() throws Exception {
    when(bb.getConversationIcon(anyString())).thenReturn(Optional.of(photo("png")));
    when(context.consumeMessageResponseQuota()).thenReturn(false);
    assertEquals("rate limited", run());
    verify(bb, never()).sendMultipartMessage(anyString(), any(), anyList());
    when(context.consumeMessageResponseQuota()).thenReturn(true);
    assertTrue(run().startsWith("failed:"));
    when(bb.sendMultipartMessage(anyString(), isNull(), anyList()))
        .thenThrow(new IllegalStateException("password=secret"));
    String error = run();
    assertTrue(error.startsWith("failed:"));
    assertFalse(error.contains("secret"));
    verify(context, never()).recordAssistantTurn(anyString());
  }

  private String run() {
    return provider.getTool().handler().apply(context, new ObjectMapper().createObjectNode());
  }

  private static IncomingMessage message(String transport, String guid, boolean group) {
    return new IncomingMessage(
        transport,
        guid,
        "incoming",
        null,
        "show group photo",
        false,
        "iMessage",
        "alice@example.com",
        group,
        Instant.EPOCH,
        List.of(),
        false);
  }

  private static byte[] photo(String format) throws Exception {
    var output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output));
    return output.toByteArray();
  }
}

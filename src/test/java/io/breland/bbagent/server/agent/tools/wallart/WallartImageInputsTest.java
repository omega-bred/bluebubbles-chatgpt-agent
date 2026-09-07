package io.breland.bbagent.server.agent.tools.wallart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.tools.wallart.WallartImageInputs.ImageReference;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WallartImageInputsTest {
  @TempDir Path directory;
  private final BBHttpClientWrapper bb = mock(BBHttpClientWrapper.class);
  private final WallartMcpClient client = mock(WallartMcpClient.class);
  private final WallartImageInputs inputs = new WallartImageInputs(bb, client, new ObjectMapper());

  @Test
  void forwardsConvertedHeicBytesAndCleansDownloadedFile() throws Exception {
    Path downloaded = directory.resolve("converted.bin");
    byte[] bytes = Base64.getDecoder().decode(png());
    Files.write(downloaded, bytes);
    when(bb.getAttachment("photo-guid")).thenReturn(downloaded);
    var message =
        message(
            List.of(
                new IncomingAttachment("photo-guid", "image/heic", "IMG.HEIC", null, null, null)));
    var result = inputs.resolve(message, List.of(ref(1)));
    assertEquals("image/png", result.getFirst().get("mime_type"));
    assertArrayEquals(bytes, Base64.getDecoder().decode((String) result.getFirst().get("data")));
    assertFalse(Files.exists(downloaded));
  }

  @Test
  void preservesImageSelectionOrderAndStripsDataUrlPrefix() throws Exception {
    String data = png();
    var message =
        message(
            List.of(
                new IncomingAttachment(null, "application/pdf", "doc.pdf", null, null, "pdf"),
                new IncomingAttachment(
                    null, "image/png", "first.png", null, "data:image/png;base64," + data, null),
                new IncomingAttachment(null, "image/png", "second.png", null, null, data)));
    var result =
        inputs.resolve(
            message,
            List.of(
                new ImageReference("attachment", 2, null, "second"),
                new ImageReference("attachment", 1, null, "first")));
    assertEquals("second", result.get(0).get("description"));
    assertEquals("first", result.get(1).get("description"));
    assertEquals(data, result.get(1).get("data"));
    verifyNoInteractions(bb);
  }

  @Test
  void resolvesRepliedToPhotoOnlyInCurrentConversation() throws Exception {
    Message prior =
        new Message()
            .chats(List.of(new Chat().guid("chat")))
            .attachments(List.of(Map.of("guid", "attachment-guid", "mimeType", "image/jpeg")));
    when(bb.getMessage("prior-message")).thenReturn(prior);
    Path downloaded = directory.resolve("photo");
    Files.write(downloaded, Base64.getDecoder().decode(png()));
    when(bb.getAttachment("attachment-guid")).thenReturn(downloaded);
    var incoming = message(List.of()).withThreadOriginatorGuid("prior-message");
    assertEquals(1, inputs.resolve(incoming, List.of(ref(1))).size());
    verify(bb).getMessage("prior-message");
    assertFalse(Files.exists(downloaded));
  }

  @Test
  void rejectsCrossConversationAndUnverifiableReferencesBeforeDownloading() {
    when(bb.getMessage("foreign"))
        .thenReturn(
            new Message()
                .chats(List.of(new Chat().guid("other-chat")))
                .attachments(List.of(Map.of("guid", "secret", "mimeType", "image/png"))));
    when(bb.getMessage("unknown")).thenReturn(new Message());
    for (String guid : List.of("foreign", "unknown")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              inputs.resolve(
                  message(List.of()), List.of(new ImageReference("attachment", 1, guid, null))));
    }
    verify(bb, never()).getAttachment(anyString());
  }

  @Test
  void supportsExplicitEarlierMessageAndDownloadsMetadataOnce() throws Exception {
    when(bb.getMessage("older"))
        .thenReturn(
            new Message()
                .chats(List.of(new Chat().guid("chat")))
                .attachments(List.of(Map.of("base64", png(), "mimeType", "image/png"))));
    var ref = new ImageReference("attachment", 1, "older", null);
    assertEquals(2, inputs.resolve(message(List.of()), List.of(ref, ref)).size());
    verify(bb).getMessage("older");
  }

  @Test
  void rejectsMissingInvalidOversizeAndUrlOnlyInputs() throws Exception {
    var incoming = message(List.of());
    assertThrows(IllegalArgumentException.class, () -> inputs.resolve(incoming, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> inputs.resolve(incoming, Collections.nCopies(17, ref(1))));
    assertThrows(IllegalArgumentException.class, () -> inputs.resolve(incoming, List.of(ref(0))));
    assertThrows(IllegalArgumentException.class, () -> WallartImageInputs.decode("invalid!"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WallartImageInputs.decode(Base64.getEncoder().encodeToString(new byte[0])));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WallartImageInputs.decode(
                "a".repeat(4 * ((WallartImageInputs.MAX_IMAGE_BYTES + 2) / 3) + 1)));
    var urlOnly =
        message(
            List.of(
                new IncomingAttachment(
                    null, "image/png", null, "http://localhost/private", null, null)));
    assertThrows(IllegalArgumentException.class, () -> inputs.resolve(urlOnly, List.of(ref(1))));
    verifyNoInteractions(bb, client);
  }

  @Test
  void enforcesTotalLimitAndDeletesRejectedDownload() throws Exception {
    byte[] original = Base64.getDecoder().decode(png());
    byte[] large = java.util.Arrays.copyOf(original, 8 * 1024 * 1024);
    var incoming =
        message(
            List.of(
                new IncomingAttachment(
                    null,
                    "image/png",
                    null,
                    null,
                    null,
                    Base64.getEncoder().encodeToString(large))));
    assertThrows(
        IllegalArgumentException.class,
        () -> inputs.resolve(incoming, Collections.nCopies(3, ref(1))));
    Path oversized = directory.resolve("oversized");
    try (var file = new java.io.RandomAccessFile(oversized.toFile(), "rw")) {
      file.setLength(WallartImageInputs.MAX_IMAGE_BYTES + 1);
    }
    when(bb.getAttachment("oversized")).thenReturn(oversized);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inputs.resolve(
                message(
                    List.of(
                        new IncomingAttachment("oversized", "image/png", null, null, null, null))),
                List.of(ref(1))));
    assertFalse(Files.exists(oversized));
  }

  @Test
  void rejectsHugeDimensionsUnsupportedFormatsAndCleansCorruptDownload() throws Exception {
    byte[] bytes = Base64.getDecoder().decode(png());
    // PNG IHDR dimensions are inspected before decoding or allocating a raster.
    java.nio.ByteBuffer.wrap(bytes).putInt(16, 5000).putInt(20, 5000);
    assertThrows(
        IllegalArgumentException.class,
        () -> WallartImageInputs.decode(Base64.getEncoder().encodeToString(bytes)));
    ByteArrayOutputStream gif = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "gif", gif);
    assertThrows(
        IllegalArgumentException.class,
        () -> WallartImageInputs.decode(Base64.getEncoder().encodeToString(gif.toByteArray())));
    Path corrupt = directory.resolve("corrupt");
    Files.writeString(corrupt, "not an image");
    when(bb.getAttachment("bad")).thenReturn(corrupt);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inputs.resolve(
                message(
                    List.of(new IncomingAttachment("bad", "image/png", null, null, null, null))),
                List.of(ref(1))));
    assertFalse(Files.exists(corrupt));
  }

  static String png() throws Exception {
    var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    return Base64.getEncoder().encodeToString(bytes.toByteArray());
  }

  private static ImageReference ref(int index) {
    return new ImageReference("attachment", index, null, null);
  }

  private static IncomingMessage message(List<IncomingAttachment> attachments) {
    return new IncomingMessage(
        "chat",
        "incoming",
        null,
        "compose",
        false,
        "iMessage",
        "+18033861737",
        false,
        Instant.EPOCH,
        attachments,
        false);
  }
}

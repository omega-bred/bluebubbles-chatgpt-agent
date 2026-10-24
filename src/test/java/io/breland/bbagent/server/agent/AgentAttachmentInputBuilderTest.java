package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentAttachmentInputBuilderTest {
  final BBHttpClientWrapper bb = mock(BBHttpClientWrapper.class);
  final AgentAttachmentInputBuilder builder = new AgentAttachmentInputBuilder(bb);

  IncomingMessage message(String mime) {
    return new IncomingMessage(
        "chat",
        "current",
        null,
        "use my photo",
        false,
        "iMessage",
        "+12025550101",
        false,
        Instant.EPOCH,
        List.of(new IncomingAttachment("own", mime, "original.HEIC", null, null, null)),
        false);
  }

  @Test
  void malformedOrOversizeImageBytesNeverBecomeIncorrectlyLabelledModelInput() throws Exception {
    for (byte[] bytes :
        List.of(
            new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'},
            new byte[10 * 1024 * 1024 + 1])) {
      Path path = Files.createTempFile("bluechat-bad-image-", ".bin");
      Files.write(path, bytes);
      when(bb.getAttachment("own")).thenReturn(path);
      assertEquals(0, builder.resolve(message("image/heic")).imageCount());
      assertFalse(Files.exists(path));
    }
  }

  @Test
  void gifAndWebpModelInputsKeepTheirEncodingWithoutAConversionCodec() throws Exception {
    var gif = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "gif",
        gif);
    // Synthetic 1x1 red lossless image generated with cwebp; no JVM WebP decoder is required.
    byte[] webp = Base64.getDecoder().decode("UklGRhwAAABXRUJQVlA4TA8AAAAvAAAAAAcQ/Y/+ByKi/wEA");
    for (var image :
        java.util.Map.of("image/gif", gif.toByteArray(), "image/webp", webp).entrySet()) {
      Path path = Files.createTempFile("bluechat-preserved-image-", ".bin");
      Files.write(path, image.getValue());
      when(bb.getAttachment("own")).thenReturn(path);
      var resolved = builder.resolve(message(image.getKey()));
      assertEquals(
          List.of(
              "data:"
                  + image.getKey()
                  + ";base64,"
                  + Base64.getEncoder().encodeToString(image.getValue())),
          resolved.imageUrls());
      assertFalse(Files.exists(path));
    }
  }

  @Test
  void nonImageFilesKeepTheirBytesAndFileHandling() throws Exception {
    byte[] pdf = "%PDF-1.7\nsynthetic file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Path path = Files.createTempFile("bluechat-file-", ".pdf");
    Files.write(path, pdf);
    when(bb.getAttachment("own")).thenReturn(path);
    var resolved = builder.resolve(message("application/pdf"));
    assertEquals(0, resolved.imageCount());
    assertEquals(1, resolved.fileCount());
    assertTrue(
        resolved.files().getFirst().toString().contains(Base64.getEncoder().encodeToString(pdf)));
    assertFalse(Files.exists(path));
  }
}

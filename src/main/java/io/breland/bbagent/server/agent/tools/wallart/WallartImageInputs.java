package io.breland.bbagent.server.agent.tools.wallart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** Resolve conversation-scoped references outside model arguments and workflow history. */
@Component
@RequiredArgsConstructor
public class WallartImageInputs {
  static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
  static final int MAX_TOTAL_BYTES = 20 * 1024 * 1024;
  private final BBHttpClientWrapper blueBubbles;
  private final WallartMcpClient client;
  private final ObjectMapper mapper;

  public record ImageReference(
      String source, Integer index, String messageGuid, String description) {}

  public List<Map<String, Object>> resolve(
      IncomingMessage message, List<ImageReference> references) {
    if (references == null || references.isEmpty() || references.size() > 16) {
      throw new IllegalArgumentException("Provide between 1 and 16 image references.");
    }
    Map<String, List<IncomingAttachment>> messages = new HashMap<>();
    ImageData currentArt = null;
    List<Map<String, Object>> images = new ArrayList<>();
    long total = 0;
    for (ImageReference reference : references) {
      if (reference == null
          || (reference.description() != null && reference.description().length() > 500)) {
        throw new IllegalArgumentException(
            "Each image needs a reference and a description of at most 500 characters.");
      }
      ImageData image;
      if ("current_art".equals(reference.source())) {
        if (reference.index() != null || StringUtils.isNotBlank(reference.messageGuid())) {
          throw new IllegalArgumentException(
              "current_art does not accept an index or messageGuid.");
        }
        if (currentArt == null) {
          var content = client.getCurrentArt();
          currentArt = decode(content.data());
        }
        image = currentArt;
      } else if ("attachment".equals(reference.source())) {
        String messageGuid = StringUtils.trimToNull(reference.messageGuid());
        if (messageGuid == null) {
          messageGuid = message.messageGuid();
          if ((message.attachments() == null || message.attachments().isEmpty())) {
            messageGuid =
                StringUtils.firstNonBlank(
                    message.replyToGuid(), message.threadOriginatorGuid(), messageGuid);
          }
        }
        List<IncomingAttachment> attachments =
            messages.computeIfAbsent(messageGuid, guid -> attachments(message, guid));
        int index = reference.index() == null ? 1 : reference.index();
        if (index < 1 || index > attachments.size()) {
          throw new IllegalArgumentException(
              "Image index is unavailable. Attach the photo with your prompt, reply to its message, or select its messageGuid from this conversation.");
        }
        image = load(attachments.get(index - 1));
      } else {
        throw new IllegalArgumentException("Image source must be attachment or current_art.");
      }
      total += image.bytes().length;
      if (total > MAX_TOTAL_BYTES) {
        throw new IllegalArgumentException("Reference images must total at most 20 MiB.");
      }
      Map<String, Object> input = new LinkedHashMap<>();
      input.put("data", Base64.getEncoder().encodeToString(image.bytes()));
      input.put("mime_type", image.mimeType());
      if (StringUtils.isNotBlank(reference.description()))
        input.put("description", reference.description());
      images.add(input);
    }
    return images;
  }

  private List<IncomingAttachment> attachments(IncomingMessage incoming, String messageGuid) {
    List<IncomingAttachment> attachments;
    if (java.util.Objects.equals(incoming.messageGuid(), messageGuid)) {
      attachments = incoming.attachments();
    } else {
      var message = blueBubbles.getMessage(messageGuid);
      if (message == null
          || message.getChats() == null
          || message.getChats().stream()
              .noneMatch(chat -> chat != null && incoming.chatGuid().equals(chat.getGuid()))) {
        throw new IllegalArgumentException(
            "The selected image message is not in this conversation.");
      }
      attachments =
          message.getAttachments() == null
              ? List.of()
              : message.getAttachments().stream()
                  .map(value -> mapper.<JsonNode>valueToTree(value))
                  .map(
                      node ->
                          new IncomingAttachment(
                              text(node, "guid"),
                              text(node, "mimeType", "mime_type"),
                              text(node, "transferName", "filename"),
                              null,
                              text(node, "dataUrl"),
                              text(node, "base64")))
                  .toList();
    }
    return attachments == null
        ? List.of()
        : attachments.stream()
            .filter(
                attachment ->
                    attachment != null
                        && (StringUtils.isBlank(attachment.mimeType())
                            || attachment.mimeType().startsWith("image/")))
            .toList();
  }

  private ImageData load(IncomingAttachment attachment) {
    // BlueBubbles original=0 converts iPhone HEIC attachments. Inspect downloaded bytes,
    // since the webhook MIME type still describes the original HEIC.
    if (StringUtils.isNotBlank(attachment.guid())) {
      Path path = blueBubbles.getAttachment(attachment.guid());
      if (path == null)
        throw new IllegalArgumentException("Unable to download the selected image.");
      try {
        if (Files.size(path) > MAX_IMAGE_BYTES)
          throw new IllegalArgumentException("Each image must be at most 10 MiB.");
        return inspect(Files.readAllBytes(path));
      } catch (IOException e) {
        throw new IllegalStateException("Unable to read the selected image", e);
      } finally {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          /* best effort */
        }
      }
    }
    if (StringUtils.isNotBlank(attachment.base64())) return decode(attachment.base64());
    String dataUrl = attachment.dataUrl();
    if (dataUrl != null && dataUrl.startsWith("data:image/") && dataUrl.contains(";base64,")) {
      return decode(dataUrl.substring(dataUrl.indexOf(";base64,") + 8));
    }
    throw new IllegalArgumentException(
        "The selected image has no downloadable attachment. Attach a PNG or JPEG photo.");
  }

  static ImageData decode(String base64) {
    if (base64 == null || base64.length() > 4 * ((MAX_IMAGE_BYTES + 2) / 3)) {
      throw new IllegalArgumentException("Each image must be at most 10 MiB.");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Image data is not valid base64.");
    }
    return inspect(bytes);
  }

  private static ImageData inspect(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
      throw new IllegalArgumentException("Each image must contain data and be at most 10 MiB.");
    }
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IllegalArgumentException("Use a PNG or JPEG photo.");
      var reader = readers.next();
      try {
        reader.setInput(input);
        String format = reader.getFormatName();
        String mime =
            "png".equalsIgnoreCase(format)
                ? "image/png"
                : "jpeg".equalsIgnoreCase(format) ? "image/jpeg" : null;
        if (mime == null) throw new IllegalArgumentException("Use a PNG or JPEG photo.");
        long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
        if (pixels <= 0 || pixels > 20_000_000)
          throw new IllegalArgumentException("Each image must contain at most 20 million pixels.");
        return new ImageData(bytes, mime);
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("The selected image could not be read.");
    }
  }

  private static String text(JsonNode node, String... names) {
    for (String name : names) if (node.path(name).isTextual()) return node.path(name).asText();
    return null;
  }

  record ImageData(byte[] bytes, String mimeType) {}
}

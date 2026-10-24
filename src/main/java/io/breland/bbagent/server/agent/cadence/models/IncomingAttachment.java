package io.breland.bbagent.server.agent.cadence.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;

public record IncomingAttachment(
    String guid, String mimeType, String filename, String url, String dataUrl, String base64) {
  /** History references contain identifiers, never attachment bytes or remote URLs. */
  public static List<IncomingAttachment> fromHistory(List<?> values, ObjectMapper mapper) {
    if (values == null) return List.of();
    return values.stream()
        .filter(java.util.Objects::nonNull)
        .map(value -> mapper.<JsonNode>valueToTree(value))
        .map(
            node ->
                new IncomingAttachment(
                    text(node, "guid"),
                    text(node, "mimeType", "mime_type"),
                    text(node, "transferName", "filename"),
                    null,
                    null,
                    null))
        .toList();
  }

  public boolean mayBeImage() {
    return mimeType == null || mimeType.isBlank() || mimeType.startsWith("image/");
  }

  /** Locators only; callers must separately establish the current message's sender authority. */
  public static List<IncomingAttachment> downloadableImages(List<IncomingAttachment> attachments) {
    if (attachments == null) return List.of();
    return attachments.stream()
        .filter(java.util.Objects::nonNull)
        .filter(IncomingAttachment::mayBeImage)
        .filter(attachment -> attachment.guid() != null && !attachment.guid().isBlank())
        .toList();
  }

  /** Encode filenames/MIME/locators as source data, never as instructions or image bytes. */
  public static ArrayNode imageChoices(List<IncomingAttachment> attachments) {
    var result = JsonNodeFactory.instance.arrayNode();
    int index = 1;
    for (var image : downloadableImages(attachments)) {
      result
          .addObject()
          .put("image_index", index++)
          .put("attachment_guid", image.guid())
          .put("filename", image.filename())
          .put("mime_type", image.mimeType());
    }
    return result;
  }

  private static String text(JsonNode node, String... fields) {
    for (String field : fields) if (node.path(field).isTextual()) return node.path(field).asText();
    return null;
  }
}

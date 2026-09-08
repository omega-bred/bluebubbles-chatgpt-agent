package io.breland.bbagent.server.agent.cadence.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

  private static String text(JsonNode node, String... fields) {
    for (String field : fields) if (node.path(field).isTextual()) return node.path(field).asText();
    return null;
  }
}

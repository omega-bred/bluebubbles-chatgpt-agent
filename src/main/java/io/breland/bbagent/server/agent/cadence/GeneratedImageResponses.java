package io.breland.bbagent.server.agent.cadence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Keep image blob references across model/tool loops until chat delivery. */
final class GeneratedImageResponses {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Map<String, ObjectNode> pending = new LinkedHashMap<>();
  private final Set<String> attempted = new HashSet<>();

  void capture(String responseJson) {
    try {
      for (JsonNode item : JSON.readTree(responseJson).path("output")) {
        String id = item.path("id").asText();
        if (item instanceof ObjectNode object
            && isImage(item)
            && "completed".equals(item.path("status").asText())
            && !id.isBlank()
            && !attempted.contains(id)) {
          ObjectNode reference = object.deepCopy();
          // Bytes are hydrated by the existing activity from the conversation's blob store.
          reference.remove("result");
          pending.put(id, reference);
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("Unable to retain generated image references", e);
    }
  }

  String takeForDelivery(String finalResponseJson) {
    if (pending.isEmpty() && attempted.isEmpty()) return finalResponseJson;
    try {
      ObjectNode response = (ObjectNode) JSON.readTree(finalResponseJson);
      var output = JSON.createArrayNode();
      for (JsonNode item : response.path("output")) {
        String id = item.path("id").asText();
        if (!isImage(item) || (!pending.containsKey(id) && !attempted.contains(id))) {
          output.add(item);
        }
      }
      pending.values().forEach(output::add);
      response.set("output", output);
      // Unconfirmed sends must not cause the same photo to be retried on an empty-text loop.
      attempted.addAll(pending.keySet());
      pending.clear();
      return JSON.writeValueAsString(response);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to prepare generated image references", e);
    }
  }

  private static boolean isImage(JsonNode item) {
    return "image_generation_call".equals(item.path("type").asText());
  }
}

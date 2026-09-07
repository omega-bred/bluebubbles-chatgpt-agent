package io.breland.bbagent.server.agent.cadence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GeneratedImageResponsesTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void keepsOrderedUniqueReferencesWithoutImageBytes() throws Exception {
    var images = new GeneratedImageResponses();
    images.capture(response("ig-first", "completed", "private-image-bytes"));
    images.capture(response("ig-first", "completed", "private-image-bytes"));
    images.capture(response("ig-failed", "failed", ""));
    images.capture(response("ig-pending", "in_progress", ""));
    images.capture(response("ig-second", "completed", "another-image"));
    String merged =
        images.takeForDelivery(
            "{\"id\":\"final-response\",\"output\":[{\"type\":\"message\",\"id\":\"caption\"}]}");
    var json = mapper.readTree(merged);
    assertEquals("final-response", json.path("id").asText());
    assertEquals(3, json.path("output").size());
    assertEquals("caption", json.path("output").get(0).path("id").asText());
    assertEquals("ig-first", json.path("output").get(1).path("id").asText());
    assertEquals("ig-second", json.path("output").get(2).path("id").asText());
    assertFalse(merged.contains("result"));
    assertFalse(merged.contains("private-image-bytes"));
  }

  @Test
  void doesNotResendAttemptedImagesDuringEmptyTextRetries() throws Exception {
    var images = new GeneratedImageResponses();
    String first = response("ig-first", "completed", "data");
    images.capture(first);
    assertEquals(1, mapper.readTree(images.takeForDelivery(first)).path("output").size());
    images.capture(first);
    assertTrue(mapper.readTree(images.takeForDelivery(first)).path("output").isEmpty());
    images.capture(response("ig-new", "completed", "data"));
    var next = mapper.readTree(images.takeForDelivery(first)).path("output");
    assertEquals(1, next.size());
    assertEquals("ig-new", next.get(0).path("id").asText());
  }

  @Test
  void ordinaryTextResponsesAreUnchanged() {
    var images = new GeneratedImageResponses();
    images.capture("{}");
    assertEquals("{}", images.takeForDelivery("{}"));
  }

  private static String response(String id, String status, String result) {
    return "{\"output\":[{\"id\":\""
        + id
        + "\",\"type\":\"image_generation_call\",\"status\":\""
        + status
        + "\",\"result\":\""
        + result
        + "\"}]}";
  }
}

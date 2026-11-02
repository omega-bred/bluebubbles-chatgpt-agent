package io.breland.bbagent.server.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.tools.gcal.CreateEventAgentTool.CreateEventRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonSchemaUtilitiesTest {
  private static final ObjectMapper TOOL_MAPPER = new ObjectMapper();

  @Test
  void calendarSchemaPreservesWireNamesRequiredFieldsAndArrayItems() {
    JsonNode schema =
        TOOL_MAPPER.valueToTree(JsonSchemaUtilities.jsonSchema(CreateEventRequest.class));
    JsonNode properties = schema.path("properties");

    assertThat(schema.path("type").asText()).isEqualTo("object");
    assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    assertThat(fieldNames(properties))
        .containsExactlyInAnyOrder(
            "account_key",
            "calendar_id",
            "summary",
            "description",
            "location",
            "start",
            "end",
            "timezone",
            "attendees");
    assertThat(properties.path("account_key").path("type").asText()).isEqualTo("string");
    assertThat(properties.path("summary").path("description").asText())
        .isEqualTo("Event summary/title.");
    assertThat(properties.path("attendees").path("type").asText()).isEqualTo("array");
    assertThat(properties.path("attendees").path("items").path("type").asText())
        .isEqualTo("string");
    assertThat(TOOL_MAPPER.convertValue(schema.path("required"), List.class))
        .containsExactlyInAnyOrder("summary", "start", "end");
  }

  @Test
  void schemaDoesNotExposeConstructorOnlyPropertiesThatToolJacksonMapperDoesNotSerialize() {
    JsonNode schema =
        TOOL_MAPPER.valueToTree(JsonSchemaUtilities.jsonSchema(ConstructorRequest.class));
    JsonNode wire = TOOL_MAPPER.valueToTree(new ConstructorRequest("id-1", "hello"));

    assertThat(fieldNames(schema.path("properties")))
        .containsExactlyInAnyOrderElementsOf(fieldNames(wire));
    assertThat(schema.path("properties").has("text")).isFalse();
    assertThat(fieldNames(wire)).containsExactlyInAnyOrder("id", "message");
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  public static class ConstructorRequest {
    private final String id;
    private final String message;

    public ConstructorRequest(String id, String text) {
      this.id = id;
      this.message = text;
    }

    public String getId() {
      return id;
    }

    public String getMessage() {
      return message;
    }
  }
}

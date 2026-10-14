package io.breland.bbagent.server.agent.tools.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.breland.bbagent.server.agent.tools.AgentTool;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ToolSearchAgentToolTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @ParameterizedTest
  @MethodSource("textArgumentCases")
  void preservesTextCoercionAndCategoryNormalization(
      String inputJson, boolean searches, String expectedCategory) throws Exception {
    List<String> categories = new ArrayList<>();
    AgentTool tool =
        new ToolSearchAgentTool(
                mapper,
                (message, category) -> {
                  assertThat(message).isNull();
                  categories.add(category);
                  return List.of();
                })
            .getTool();
    JsonNode args = inputJson == null ? null : mapper.readTree(inputJson);

    assertThat(tool.handler().apply(null, args)).isEqualTo("[]");
    assertThat(categories).hasSize(searches ? 1 : 0);
    if (searches) {
      assertThat(categories.getFirst()).isEqualTo(expectedCategory);
    }
  }

  private static Stream<Arguments> textArgumentCases() {
    return Stream.of(
        Arguments.of(null, false, null),
        Arguments.of("null", false, null),
        Arguments.of("[]", false, null),
        Arguments.of("17", false, null),
        Arguments.of("\"root\"", false, null),
        Arguments.of("{}", false, null),
        Arguments.of("{\"query\":null}", false, null),
        Arguments.of("{\"query\":\"\"}", false, null),
        Arguments.of("{\"query\":\"  \\t  \"}", false, null),
        Arguments.of("{\"query\":[]}", false, null),
        Arguments.of("{\"query\":{}}", false, null),
        Arguments.of("{\"query\":\"calendar\"}", true, null),
        Arguments.of("{\"query\":true}", true, null),
        Arguments.of("{\"query\":7}", true, null),
        Arguments.of("{\"query\":\"calendar\",\"categoryFilter\":null}", true, null),
        Arguments.of("{\"query\":\"calendar\",\"categoryFilter\":\"  \"}", true, null),
        Arguments.of("{\"query\":\"calendar\",\"categoryFilter\":[]}", true, null),
        Arguments.of("{\"query\":\"calendar\",\"categoryFilter\":{}}", true, null),
        Arguments.of("{\"query\":\" calendar \",\"categoryFilter\":\" memory \"}", true, "memory"),
        Arguments.of("{\"query\":\"calendar\",\"categoryFilter\":false}", true, "false"),
        Arguments.of("{\"query\":\"calendar\",\"categoryFilter\":7}", true, "7"));
  }

  @ParameterizedTest
  @MethodSource("maxResultsCases")
  void preservesIntegerCoercionDefaultAndClamping(String valueJson, int expectedCount)
      throws Exception {
    List<ToolSearchAgentTool.ToolIndexEntry> entries =
        IntStream.range(0, 10)
            .mapToObj(
                index -> new ToolSearchAgentTool.ToolIndexEntry("calendar_" + index, "calendar"))
            .toList();
    AgentTool tool = new ToolSearchAgentTool(mapper, (message, category) -> entries).getTool();
    // Unique tool names score above the production threshold even in this uniform fixture.
    String query =
        String.join(
            " ", entries.stream().map(ToolSearchAgentTool.ToolIndexEntry::toolName).toList());
    ObjectNode args = mapper.createObjectNode().put("query", query);
    if (valueJson != null) {
      args.set("maxResults", mapper.readTree(valueJson));
    }

    JsonNode matches = mapper.readTree(tool.handler().apply(null, args));

    assertThat(matches.isArray()).isTrue();
    assertThat(matches.size()).isEqualTo(expectedCount);
    for (JsonNode match : matches) {
      assertThat(match.asText()).startsWith("calendar_");
    }
  }

  private static Stream<Arguments> maxResultsCases() {
    return Stream.of(
        Arguments.of(null, 5),
        Arguments.of("null", 5),
        Arguments.of("3", 3),
        Arguments.of("3.9", 3),
        Arguments.of("0", 1),
        Arguments.of("-4", 1),
        Arguments.of("22", 10),
        Arguments.of("2147483648", 5),
        Arguments.of("-2147483649", 5),
        Arguments.of("\"3\"", 3),
        Arguments.of("\"+3\"", 3),
        Arguments.of("\"03\"", 3),
        Arguments.of("\"-4\"", 1),
        Arguments.of("\"22\"", 10),
        Arguments.of("\" 3 \"", 5),
        Arguments.of("\"3.0\"", 5),
        Arguments.of("\"3e0\"", 5),
        Arguments.of("\"2147483648\"", 5),
        Arguments.of("\"\"", 5),
        Arguments.of("\"invalid\"", 5),
        Arguments.of("true", 5),
        Arguments.of("{}", 5),
        Arguments.of("[]", 5));
  }
}

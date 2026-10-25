package io.breland.bbagent.server.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SubscriptionJsonTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @ParameterizedTest
  @MethodSource("fieldValues")
  void firstTextPreservesPriorityAndScalarConversion(String preferred, String expected)
      throws Exception {
    JsonNode node = JSON.readTree("{\"preferred\":" + preferred + ",\"fallback\":\"fallback\"}");

    assertThat(SubscriptionJson.firstText(node, "preferred", "fallback")).isEqualTo(expected);
  }

  private static Stream<Arguments> fieldValues() {
    return Stream.of(
        Arguments.of("\" preferred \"", " preferred "),
        Arguments.of("\"雪\\n🙂\"", "雪\n🙂"),
        Arguments.of("\"\"", "fallback"),
        Arguments.of("\" \\t\\n\"", "fallback"),
        Arguments.of("\"\\u2003\"", "fallback"),
        Arguments.of("\"\\u00a0\"", "\u00a0"),
        Arguments.of("null", "fallback"),
        Arguments.of("{}", "fallback"),
        Arguments.of("[]", "fallback"),
        Arguments.of("{\"fallback\":\"nested\"}", "fallback"),
        Arguments.of("0", "0"),
        Arguments.of("-42", "-42"),
        Arguments.of("1.25", "1.25"),
        Arguments.of("9223372036854775808", "9223372036854775808"),
        Arguments.of("true", "true"),
        Arguments.of("false", "false"));
  }

  @ParameterizedTest
  @MethodSource("emptyRoots")
  void firstTextReturnsNullForAbsentOrNonObjectRoots(JsonNode root) {
    assertThat(SubscriptionJson.firstText(root, "preferred", "fallback")).isNull();
  }

  private static Stream<JsonNode> emptyRoots() {
    return Stream.of(
        null, JSON.nullNode(), JSON.missingNode(), JSON.createArrayNode(), JSON.valueToTree(42));
  }

  @Test
  void firstTextSkipsMissingFieldsAndKeepsRequestedFieldOrder() throws Exception {
    JsonNode node = JSON.readTree("{\"second\":\"two\",\"first\":\"one\"}");

    assertThat(SubscriptionJson.firstText(node, "missing", "first", "second")).isEqualTo("one");
    assertThat(SubscriptionJson.firstText(node, "second", "first")).isEqualTo("two");
    assertThat(SubscriptionJson.firstText(node, "missing")).isNull();
    assertThat(SubscriptionJson.firstText(node)).isNull();
  }

  @Test
  void firstTextReturnsNullWhenEveryRequestedValueIsBlank() throws Exception {
    JsonNode node = JSON.readTree("{\"first\":\"\",\"second\":\" \\t\",\"third\":null}");

    assertThat(SubscriptionJson.firstText(node, "first", "second", "third", "missing")).isNull();
  }

  @Test
  void absentRootsStillShortCircuitNullFieldNames() {
    assertThat(SubscriptionJson.firstText(null, (String[]) null)).isNull();
    assertThat(SubscriptionJson.firstText(JSON.nullNode(), (String[]) null)).isNull();
    assertThat(SubscriptionJson.firstText(JSON.missingNode(), (String[]) null)).isNull();
    assertThatNullPointerException()
        .isThrownBy(() -> SubscriptionJson.firstText(JSON.createObjectNode(), (String[]) null));
  }
}

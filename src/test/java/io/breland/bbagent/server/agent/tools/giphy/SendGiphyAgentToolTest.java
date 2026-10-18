package io.breland.bbagent.server.agent.tools.giphy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

class SendGiphyAgentToolTest {
  @ParameterizedTest
  @MethodSource("indexOutputs")
  void parsesOnlyTheFirstSignedDecimalInteger(String output, Integer expected) {
    Integer actual =
        ReflectionTestUtils.invokeMethod(SendGiphyAgentTool.class, "parseIndex", (Object) output);

    assertThat(actual).isEqualTo(expected);
  }

  private static Stream<Arguments> indexOutputs() {
    return Stream.of(
        Arguments.of(null, null),
        Arguments.of("", null),
        Arguments.of(" \t\n", null),
        Arguments.of("no candidate", null),
        Arguments.of("-", null),
        Arguments.of("NaN", null),
        Arguments.of("0", 0),
        Arguments.of("1", 1),
        Arguments.of("-0", 0),
        Arguments.of("0003", 3),
        Arguments.of("-0003", -3),
        Arguments.of("+3", 3),
        Arguments.of("choose index 2", 2),
        Arguments.of("index: -2", -2),
        Arguments.of("1 then 2", 1),
        Arguments.of("2147483647", Integer.MAX_VALUE),
        Arguments.of("-2147483648", Integer.MIN_VALUE),
        Arguments.of("2147483648 then 2", null),
        Arguments.of("-2147483649 then 1", null),
        Arguments.of("9999999999999999999999999999999999999 then 1", null),
        Arguments.of("0000000000000000000000000000000000001", 1),
        Arguments.of("-0000000000000000000000000000000000000", 0),
        Arguments.of("1.9", 1),
        Arguments.of("-1.9", -1),
        Arguments.of("1e3", 1),
        Arguments.of("1_2", 1),
        Arguments.of("a-3b4", -3),
        Arguments.of("--3", -3),
        Arguments.of("１２", null),
        Arguments.of("١٢", null),
        Arguments.of("candidate 😀 2", 2));
  }
}

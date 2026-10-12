package io.breland.bbagent.server.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.JournalMessage;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConversationMemoryModelsTest {
  @ParameterizedTest
  @MethodSource("corpusHashCases")
  void hashesTheExactOrderedUtf8JournalFormat(List<JournalMessage> messages, String expected) {
    assertThat(ConversationMemoryModels.corpusHash(messages)).isEqualTo(expected);
  }

  private static Stream<Arguments> corpusHashCases() {
    JournalMessage first = message("message-1", "hash-1");
    JournalMessage second = message("message-2", "hash-2");
    return Stream.of(
        Arguments.of(List.of(), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        Arguments.of(
            List.of(first), "9e98d3b5d5a532fbbb63e291ce592375402dbbde0f09e496bbb436483b200aac"),
        Arguments.of(
            List.of(first, second),
            "5d30edcbeae94d6cfcad31808c57b5c320b180ca73092a5589e4dcaf002aaaed"),
        Arguments.of(
            List.of(second, first),
            "a08e9c76a10a92180bf467a07c5575d2266e4354265c6f0f0445ee78d04700d7"),
        Arguments.of(
            List.of(message("会話-😀", "café-💬")),
            "9f96c16fb5bb0b3666e25ccea9d441a6e51d7600cc859fbb74033370c7d75bce"),
        Arguments.of(
            List.of(message("", "")),
            "67ebbd370daa02ba9aadd05d8e091e862d0d8bcadafdf2a22360240a42fe922e"),
        Arguments.of(
            List.of(message("a\0b", "c\nd")),
            "af5003aacf1240bbb06bdac2fec9dac05670104218995a63ed1609b80c1c8c2c"),
        Arguments.of(
            List.of(first, first),
            "441a4c7065bf38114284aa0daebfe4ce33ab9d405932bb51ccdae7e0fb443d8a"));
  }

  @Test
  void rejectsNullInputsWithoutSilentlyHashingThem() {
    assertThatNullPointerException().isThrownBy(() -> ConversationMemoryModels.corpusHash(null));
    assertThatNullPointerException()
        .isThrownBy(() -> ConversationMemoryModels.corpusHash(Collections.singletonList(null)));
    assertThatNullPointerException()
        .isThrownBy(() -> ConversationMemoryModels.corpusHash(List.of(message(null, "hash"))));
    assertThatNullPointerException()
        .isThrownBy(() -> ConversationMemoryModels.corpusHash(List.of(message("guid", null))));
  }

  private static JournalMessage message(String guid, String hash) {
    return new JournalMessage(guid, null, null, null, null, false, false, hash);
  }
}

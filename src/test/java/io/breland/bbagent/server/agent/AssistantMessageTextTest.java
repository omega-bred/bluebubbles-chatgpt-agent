package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AssistantMessageTextTest {
  private static final String PREFIX = "[messageGuid=11111111-2222-4333-8444-555555555555]";

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\n", "\t"})
  void removesOnlyTheLeadingMetadataAndPreservesTheReply(String separator) {
    String body = "big question! 🌉\n\nSF pros:\n- walkable + solid transit\n";

    assertEquals(
        body, AssistantMessageText.stripLeadingMessageGuid("  " + PREFIX + separator + body));
  }

  @Test
  void handlesRepeatedPrefixesAndBothUuidCases() {
    assertEquals(
        "hello",
        AssistantMessageText.stripLeadingMessageGuid(
            "[messageGuid=AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE]\n"
                + "[messageGuid=aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee] hello"));
    assertEquals("", AssistantMessageText.stripLeadingMessageGuid(PREFIX));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "  Normal reply\n",
        "The [messageGuid=11111111-2222-4333-8444-555555555555] bit is an internal ID.",
        "`[messageGuid=11111111-2222-4333-8444-555555555555]` is a quoted example.",
        "[messageGuid=example] is a literal example.",
        "[messageGuid=11111111-2222-4333-8444-55555555555] malformed UUID",
        "[chatGuid=11111111-2222-4333-8444-555555555555] different metadata",
        "Intro\n[messageGuid=11111111-2222-4333-8444-555555555555] quoted on a later line"
      })
  void preservesOrdinaryTextAndQuotedOrMalformedMarkers(String text) {
    assertEquals(text, AssistantMessageText.stripLeadingMessageGuid(text));
  }
}

package io.breland.bbagent.server.agent.transport.bb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BlueBubblesPollSupportTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "null",
        "{}",
        "[]",
        "false",
        "42",
        "\"unexpected\"",
        "{\"options\":null,\"responses\":null}",
        "{\"options\":[],\"responses\":[]}",
        "{\"options\":{},\"responses\":{}}",
        "{\"options\":\"unexpected\",\"responses\":42}"
      })
  void missingAndNonArrayFieldsKeepEmptyPollFormatting(String json) throws Exception {
    JsonNode poll = json == null ? null : mapper.readTree(json);
    assertEquals(
        """
        Poll update: Poll update: no votes recorded.
        Poll created or updated notification for poll poll-guid. Current votes: none. Trigger message GUID: unknown. Reply with a concise poll update for the chat using the Poll update summary above.""",
        BlueBubblesPollSupport.formatPollNotification(null, "poll-guid", poll));
    assertEquals(
        """
        Poll read result
        Title: (untitled)
        Options and votes: unavailable
        Responses: none
        Raw poll JSON:\s"""
            + (poll == null ? "null" : poll.toString()),
        BlueBubblesPollSupport.formatPollReadResult(poll, null, List.of()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "[]", "{}", "false", "42", "\"unexpected\""})
  void nonArrayVotesKeepOptionsAndZeroTallies(String json) throws Exception {
    JsonNode value = mapper.readTree(json);
    var poll = mapper.createObjectNode();
    poll.putArray("options").addObject().put("optionIdentifier", "a").put("text", "Sushi");
    poll.set("responses", value);
    assertZeroVotes(poll);

    poll.putArray("responses").addObject().put("handle", "Alice").set("optionIdentifiers", value);
    assertZeroVotes(poll);
  }

  private void assertZeroVotes(JsonNode poll) {
    assertTrue(
        BlueBubblesPollSupport.formatPollNotification(null, "poll-guid", poll)
            .startsWith(
                "Poll update: Poll update: no votes recorded. Tally: Sushi 0 votes. Options: Sushi.\n"));
    assertTrue(
        BlueBubblesPollSupport.formatPollReadResult(poll, null, List.of())
            .contains("1. Sushi - 0 votes [optionIdentifier=a]\nResponses: none\n"));
  }

  @Test
  void mixedArraysPreserveDuplicateVotesUnknownOptionsAndAnonymousTallies() throws Exception {
    JsonNode poll =
        mapper.readTree(
            """
            {
              "options": [
                {"optionIdentifier":"a","text":"Sushi"},
                {"optionIdentifier":"b","text":"Pizza"}
              ],
              "responses": [
                null, false, {}, {"handle":"ignored","optionIdentifiers":{}},
                {"optionIdentifiers":["a"]},
                {"handle":"Alice","optionIdentifiers":["a",null,"a","unknown"]},
                {"handle":"Bob","optionIdentifiers":["b"]}
              ]
            }
            """);
    assertEquals(
        """
        Poll update: Poll update: Alice voted for Sushi, Sushi, unknown; Bob voted for Pizza. Tally: Sushi 3 votes, Pizza 1 vote.
        Poll created or updated notification for poll poll-guid. Current options: Sushi (a); Pizza (b). Current votes: Alice voted for Sushi, Sushi, unknown; Bob voted for Pizza. Trigger message GUID: unknown. Reply with a concise poll update for the chat using the Poll update summary above.""",
        BlueBubblesPollSupport.formatPollNotification(null, "poll-guid", poll));
    assertEquals(
        """
        Poll read result
        Title: (untitled)
        Options and votes:
        1. Sushi - 2 votes (Alice, Alice) [optionIdentifier=a]
        2. Pizza - 1 vote (Bob) [optionIdentifier=b]
        Responses: Alice voted for Sushi, Sushi, unknown; Bob voted for Pizza
        Raw poll JSON:\s"""
            + poll,
        BlueBubblesPollSupport.formatPollReadResult(poll, null, List.of()));
  }
}

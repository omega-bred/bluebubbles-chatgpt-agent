package io.breland.bbagent.server.agent.decisions;

import java.util.List;

/** Response routing and tool hints; authorization is always checked again at execution. */
public record DecisionRouting(
    List<String> toolNames, boolean feedback, ResponseMode responseMode, String reaction) {
  public enum ResponseMode {
    FULL_RESPONSE,
    NO_RESPONSE,
    REACTION
  }

  public static final List<String> REACTIONS =
      List.of("like", "love", "laugh", "emphasize", "dislike", "question");
  public static final DecisionRouting EMPTY = new DecisionRouting(List.of(), false);

  public DecisionRouting(List<String> toolNames, boolean feedback) {
    this(toolNames, feedback, ResponseMode.FULL_RESPONSE, null);
  }

  public DecisionRouting {
    toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
    responseMode = responseMode == null ? ResponseMode.FULL_RESPONSE : responseMode;
    if (responseMode == ResponseMode.REACTION
        && (reaction == null || !REACTIONS.contains(reaction))) {
      responseMode = ResponseMode.FULL_RESPONSE;
    }
    if (responseMode != ResponseMode.REACTION) reaction = null;
  }
}

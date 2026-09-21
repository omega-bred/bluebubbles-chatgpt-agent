package io.breland.bbagent.server.agent.memory;

import java.util.regex.Pattern;

/** Prevents storing batch-local identity labels; semantic usefulness is assessed by extraction. */
final class GroupMemoryRetentionPolicy {
  private static final Pattern BATCH_LOCAL_PARTICIPANT =
      Pattern.compile("\\bparticipant[-\\s]+(?:\\d+|unknown)\\b", Pattern.CASE_INSENSITIVE);

  private GroupMemoryRetentionPolicy() {}

  static boolean isRetainable(String text) {
    return text != null && !text.isBlank() && !BATCH_LOCAL_PARTICIPANT.matcher(text).find();
  }
}

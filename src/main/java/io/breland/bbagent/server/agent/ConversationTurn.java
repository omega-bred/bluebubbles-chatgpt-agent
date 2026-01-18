package io.breland.bbagent.server.agent;

import com.openai.models.responses.EasyInputMessage;
import java.time.Instant;
import java.util.Objects;

public record ConversationTurn(String role, String content, Instant timestamp) {
  public static ConversationTurn user(String content, Instant timestamp) {
    return new ConversationTurn("user", content, timestamp);
  }

  public static ConversationTurn assistant(String content, Instant timestamp) {
    return new ConversationTurn("assistant", content, timestamp);
  }

  EasyInputMessage toEasyInputMessage() {
    EasyInputMessage.Role roleEnum =
        Objects.equals(role, "assistant")
            ? EasyInputMessage.Role.ASSISTANT
            : EasyInputMessage.Role.USER;
    return EasyInputMessage.builder().role(roleEnum).content(content).build();
  }
}

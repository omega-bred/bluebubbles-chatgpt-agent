package io.breland.bbagent.server.agent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;

public final class ConversationQuestionAnsweringModels {
  private ConversationQuestionAnsweringModels() {}

  public enum AnswerStatus {
    ANSWERED,
    INSUFFICIENT_EVIDENCE,
    UNAVAILABLE;

    public String wireValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public enum Confidence {
    HIGH,
    MEDIUM,
    LOW;

    public String wireValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public enum RetrievalMode {
    EXACT_SEARCH,
    CHRONOLOGICAL,
    HYBRID;

    public String wireValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public enum CoverageStatus {
    COMPLETE,
    PARTIAL;

    public String wireValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public record SearchPlan(
      List<String> terms,
      @Nullable String senderHint,
      @Nullable Instant fromHint,
      @Nullable Instant toHint) {
    public SearchPlan {
      terms = List.copyOf(terms);
      senderHint = StringUtils.trimToNull(senderHint);
    }
  }

  public record QuestionMessage(
      String messageGuid, String participant, Instant timestamp, String text) {
    public QuestionMessage {
      requireNotBlank(messageGuid, "message guid");
      requireNotBlank(participant, "participant");
      if (timestamp == null) {
        throw new IllegalArgumentException("timestamp must not be null");
      }
      requireNotBlank(text, "text");
    }
  }

  public record ModelAnswer(
      AnswerStatus status,
      String answer,
      Confidence confidence,
      List<String> evidenceMessageGuids,
      boolean needsMoreContext) {
    public ModelAnswer {
      if (status == null) {
        throw new IllegalArgumentException("answer status must not be null");
      }
      requireNotBlank(answer, "answer");
      if (confidence == null) {
        throw new IllegalArgumentException("confidence must not be null");
      }
      evidenceMessageGuids = List.copyOf(evidenceMessageGuids);
    }
  }

  public record RoutedModelAnswer(ModelAnswer answer, String model) {
    public RoutedModelAnswer {
      if (answer == null) {
        throw new IllegalArgumentException("answer must not be null");
      }
      requireNotBlank(model, "model");
    }
  }

  public record QuestionFinding(
      String answer,
      Confidence confidence,
      List<String> evidenceMessageGuids,
      Instant coverageThrough) {
    public QuestionFinding {
      requireNotBlank(answer, "answer");
      if (confidence == null) {
        throw new IllegalArgumentException("confidence must not be null");
      }
      evidenceMessageGuids = List.copyOf(evidenceMessageGuids);
      if (coverageThrough == null) {
        throw new IllegalArgumentException("coverage through must not be null");
      }
    }
  }

  private static void requireNotBlank(String value, String name) {
    if (StringUtils.isBlank(value)) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}

package io.breland.bbagent.server.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.*;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BackgroundDecisionServiceTest {
  private final DecisionsClient client = mock(DecisionsClient.class);
  private final DecisionsProperties properties = new DecisionsProperties();
  private final ObjectMapper mapper = new ObjectMapper();
  private final BackgroundDecisionService service =
      new BackgroundDecisionService(
          client, properties, mapper, new OperationalMetricsService(null));

  @Test
  void rejectsLowValueMemoryAndKeepsPayloadAlignedWithCandidates() throws Exception {
    when(client.evaluate(any(), any(), any()))
        .thenReturn(new DecisionsClient.Result(Map.of("retain_0", 0.01, "retain_1", 0.98)));
    var candidates = List.of(candidate("It rained today."), candidate("Meet every Friday."));
    var extraction =
        new ModelExtraction(
            "Weekly plan",
            candidates,
            "[{\"text\":\"It rained today.\"},{\"text\":\"Meet every Friday.\"}]");
    var reviewed = service.reviewMemory("{\"transcript\":[]}", extraction);
    assertThat(reviewed.candidates()).containsExactly(candidates.get(1));
    assertThat(mapper.readTree(reviewed.itemPayload()).get(0).path("text").asText())
        .isEqualTo("Meet every Friday.");
    assertThat(reviewed.summary()).isEqualTo("Weekly plan");
  }

  @Test
  void unknownMemoryAnswersKeepExistingExtraction() {
    when(client.evaluate(any(), any(), any())).thenReturn(DecisionsClient.Result.UNAVAILABLE);
    var extraction =
        new ModelExtraction(
            "Summary", List.of(candidate("Meet Friday")), "[{\"text\":\"Meet Friday\"}]");
    assertThat(service.reviewMemory("{}", extraction)).isEqualTo(extraction);
  }

  @Test
  void emptyExtractionDoesNotCallDecisions() {
    var extraction = new ModelExtraction("Summary", List.of(), "[]");
    assertThat(service.reviewMemory("{}", extraction)).isSameAs(extraction);
    verifyNoInteractions(client);
  }

  @Test
  void onlyConfidentlyUnhelpfulCatchupIsSuppressed() {
    when(client.evaluate(any(), any(), any()))
        .thenReturn(new DecisionsClient.Result(Map.of("notify", 0.02)));
    assertThat(service.shouldNotify(catchup())).isFalse();
    when(client.evaluate(any(), any(), any()))
        .thenReturn(new DecisionsClient.Result(Map.of("notify", 0.5)));
    assertThat(service.shouldNotify(catchup())).isTrue();
    when(client.evaluate(any(), any(), any())).thenReturn(DecisionsClient.Result.UNAVAILABLE);
    assertThat(service.shouldNotify(catchup())).isTrue();
  }

  @Test
  void disablingIndividualFeaturesAvoidsTheirCalls() {
    properties.setMemoryReviewEnabled(false);
    properties.setCatchupEnabled(false);
    properties.setTermsEnabled(false);
    var extraction = new ModelExtraction("Summary", List.of(candidate("Meet Friday")), "[]");
    assertThat(service.reviewMemory("{}", extraction)).isSameAs(extraction);
    assertThat(service.shouldNotify(catchup())).isTrue();
    assertThat(service.termsEnabled()).isFalse();
    verifyNoInteractions(client);
  }

  static ExtractionCandidate candidate(String text) {
    return new ExtractionCandidate(
        ArtifactKind.GROUP_DECISION,
        text,
        ArtifactStatus.CONFIRMED,
        ArtifactSensitivity.NORMAL,
        0.99,
        Instant.parse("2026-10-18T18:00:00Z"),
        null,
        List.of("m1"),
        null,
        "hash");
  }

  static CatchupGroup catchup() {
    return new CatchupGroup(
        "Project",
        "No change",
        List.of(),
        List.of("Keep waiting"),
        List.of(),
        Instant.EPOCH,
        Instant.EPOCH.plusSeconds(60),
        Instant.EPOCH.plusSeconds(60));
  }
}

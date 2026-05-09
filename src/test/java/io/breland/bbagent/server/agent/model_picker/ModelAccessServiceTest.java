package io.breland.bbagent.server.agent.model_picker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.persistence.model.ModelAccountSettingsEntity;
import io.breland.bbagent.server.agent.persistence.model.ModelAccountSettingsRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ModelAccessServiceTest {

  private final ModelAccountSettingsRepository repository =
      Mockito.mock(ModelAccountSettingsRepository.class);
  private final ModelAccessService service = new ModelAccessService(repository);

  @Test
  void defaultsUnconfiguredSenderToStandardFreePlan() {
    when(repository.findById("Alice")).thenReturn(Optional.empty());

    ModelAccessService.ModelAccess access = service.resolve(message());

    assertFalse(access.premium());
    assertEquals("standard", access.plan());
    assertEquals("local", access.currentModelKey());
    assertEquals("Free", access.currentModelLabel());
    assertEquals("Qwen/Qwen3.6-27B", ModelAccessService.STANDARD_RESPONSES_MODEL);
    assertEquals(ModelAccessService.STANDARD_RESPONSES_MODEL, access.responsesModel());
  }

  @Test
  void readsPremiumEntitlementFromPostgresBackedRepository() {
    when(repository.findById("Alice")).thenReturn(Optional.of(settings(true, null)));

    ModelAccessService.ModelAccess access = service.resolve(message());

    assertTrue(access.premium());
    assertEquals("premium", access.plan());
    assertEquals("chatgpt", access.currentModelKey());
    assertEquals(ModelAccessService.PREMIUM_RESPONSES_MODEL, access.responsesModel());
  }

  @Test
  void blankPremiumSelectionFallsBackToDefaultPremiumModel() {
    when(repository.findById("Alice")).thenReturn(Optional.of(settings(true, " ")));

    ModelAccessService.ModelAccess access = service.resolve(message());

    assertTrue(access.premium());
    assertEquals("chatgpt", access.currentModelKey());
    assertEquals("ChatGPT", access.currentModelLabel());
  }

  @Test
  void exposesWebsiteSummaryForReadOnlyAccountPage() {
    when(repository.findById("Alice")).thenReturn(Optional.of(settings(true, null)));

    WebsiteModelAccessSummary summary = service.toWebsiteSummary("Alice");

    assertEquals(WebsiteModelAccessSummary.PlanEnum.PREMIUM, summary.getPlan());
    assertTrue(summary.getIsPremium());
    assertEquals("chatgpt", summary.getCurrentModel());
    assertFalse(summary.getModelSelectionConfigurable());
    assertEquals(4, summary.getAvailableModels().size());
  }

  private ModelAccountSettingsEntity settings(boolean premium, String selectedModel) {
    Instant now = Instant.now();
    return new ModelAccountSettingsEntity("Alice", premium, selectedModel, now, now);
  }

  private IncomingMessage message() {
    return new IncomingMessage(
        "iMessage;+;chat-1",
        "msg-1",
        null,
        "hello",
        false,
        "iMessage",
        "Alice",
        false,
        Instant.now(),
        List.of(),
        false);
  }
}

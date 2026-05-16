package io.breland.bbagent.server.agent.model_picker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ModelAccessServiceTest {

  private final AgentAccountRepository repository = Mockito.mock(AgentAccountRepository.class);
  private final AgentAccountResolver resolver = Mockito.mock(AgentAccountResolver.class);
  private final ModelAccessService service = new ModelAccessService(repository, resolver);

  @Test
  void defaultsUnconfiguredSenderToStandardFreePlan() {
    when(resolver.resolveOrCreate(any(IncomingMessage.class)))
        .thenReturn(
            Optional.of(new AgentAccountResolver.ResolvedAccount(account(false, null), List.of())));

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
    when(resolver.resolveOrCreate(any(IncomingMessage.class)))
        .thenReturn(
            Optional.of(new AgentAccountResolver.ResolvedAccount(account(true, null), List.of())));

    ModelAccessService.ModelAccess access = service.resolve(message());

    assertTrue(access.premium());
    assertEquals("premium", access.plan());
    assertEquals("chatgpt", access.currentModelKey());
    assertEquals(ModelAccessService.PREMIUM_RESPONSES_MODEL, access.responsesModel());
  }

  @Test
  void blankPremiumModelSelectionDefaultsToChatGpt() {
    when(resolver.resolveOrCreate(any(IncomingMessage.class)))
        .thenReturn(
            Optional.of(new AgentAccountResolver.ResolvedAccount(account(true, " "), List.of())));

    ModelAccessService.ModelAccess access = service.resolve(message());

    assertEquals("chatgpt", access.currentModelKey());
    assertEquals(ModelAccessService.PREMIUM_RESPONSES_MODEL, access.responsesModel());
  }

  @Test
  void exposesWebsiteSummaryForReadOnlyAccountPage() {
    when(repository.findById("account-1")).thenReturn(Optional.of(account(true, null)));

    WebsiteModelAccessSummary summary = service.toWebsiteSummary("account-1");

    assertEquals(WebsiteModelAccessSummary.PlanEnum.PREMIUM, summary.getPlan());
    assertTrue(summary.getIsPremium());
    assertEquals("chatgpt", summary.getCurrentModel());
    assertFalse(summary.getModelSelectionConfigurable());
    assertEquals(4, summary.getAvailableModels().size());
  }

  private AgentAccountEntity account(boolean premium, String selectedModel) {
    Instant now = Instant.now();
    AgentAccountEntity account = new AgentAccountEntity("account-1", now, now);
    account.setPremium(premium);
    account.setSelectedModel(selectedModel);
    return account;
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

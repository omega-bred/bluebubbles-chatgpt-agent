package io.breland.bbagent.server.website;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.WebsiteLinkedIntegrationAccount;
import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
import io.breland.bbagent.server.agent.AgentAccountIdentifiers;
import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountIdentityEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenRepository;
import io.breland.bbagent.server.agent.tools.coder.CoderMcpClient;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class WebsiteAccountServiceTest {

  private final AgentAccountResolver accountResolver = Mockito.mock(AgentAccountResolver.class);
  private final WebsiteAccountLinkTokenRepository tokenRepository =
      Mockito.mock(WebsiteAccountLinkTokenRepository.class);
  private final GcalClient gcalClient = Mockito.mock(GcalClient.class);
  private final CoderMcpClient coderMcpClient = Mockito.mock(CoderMcpClient.class);
  private final ModelAccessService modelAccessService = Mockito.mock(ModelAccessService.class);
  private final WebsiteAccountService service =
      new WebsiteAccountService(
          accountResolver,
          tokenRepository,
          gcalClient,
          coderMcpClient,
          modelAccessService,
          "https://chatagent.example",
          30);

  @Test
  void createLinkTokenReturnsUrlAndStoresOnlyHashWithAccountId() {
    when(accountResolver.resolveOrCreate(any(IncomingMessage.class)))
        .thenReturn(Optional.of(new AgentAccountResolver.ResolvedAccount(account(), List.of())));
    when(tokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    WebsiteAccountService.CreatedLinkToken link = service.createLinkToken(message());

    assertTrue(link.url().startsWith("https://chatagent.example/account/link?token="));
    assertFalse(link.url().contains("token_hash"));
    assertEquals("account-1", link.accountId());
    ArgumentCaptor<WebsiteAccountLinkTokenEntity> captor =
        ArgumentCaptor.forClass(WebsiteAccountLinkTokenEntity.class);
    Mockito.verify(tokenRepository).save(captor.capture());
    assertEquals("account-1", captor.getValue().getAccountId());
    assertEquals(64, captor.getValue().getTokenHash().length());
  }

  @Test
  void redeemLinkAttachesWebsiteAccountToTokenAccount() {
    WebsiteAccountLinkTokenEntity tokenEntity = tokenEntity(Instant.now().plusSeconds(60));
    when(accountResolver.upsertWebsiteAccount(any(Jwt.class))).thenReturn(account());
    when(accountResolver.linkWebsiteAccount(eq("account-1"), any(Jwt.class))).thenReturn(account());
    when(tokenRepository.findById(anyString())).thenReturn(Optional.of(tokenEntity));
    when(tokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountResolver.identitiesForAccount("account-1")).thenReturn(List.of(identity()));

    var response = service.redeemLink(jwt(), "abc123");

    assertEquals("linked", response.getStatus());
    assertEquals("account-1", response.getLink().getAccountId());
    assertEquals("account-1", tokenEntity.getRedeemedAccountId());
    assertEquals(1, response.getLink().getIdentities().size());
  }

  @Test
  void redeemLinkRejectsExpiredToken() {
    when(accountResolver.upsertWebsiteAccount(any(Jwt.class))).thenReturn(account());
    when(tokenRepository.findById(anyString()))
        .thenReturn(Optional.of(tokenEntity(Instant.now().minusSeconds(1))));

    try {
      service.redeemLink(jwt(), "abc123");
    } catch (ResponseStatusException e) {
      assertEquals(HttpStatus.GONE, e.getStatusCode());
      return;
    }
    throw new AssertionError("Expected expired token rejection");
  }

  @Test
  void listLinkedAccountsAggregatesCoderAndGcalStatus() {
    when(accountResolver.upsertWebsiteAccount(any(Jwt.class))).thenReturn(account());
    when(accountResolver.identitiesForAccount("account-1")).thenReturn(List.of(identity()));
    when(coderMcpClient.isLinked("account-1")).thenReturn(true);
    when(coderMcpClient.findLinkedAccount("account-1"))
        .thenReturn(
            Optional.of(
                new CoderMcpClient.CoderLinkedAccount(
                    "account-1", "alice@coder.example", "alice")));
    when(gcalClient.listLinkedAccountsFor("account-1"))
        .thenReturn(
            List.of(
                new GcalClient.GcalLinkedAccount("account-1::primary", "account-1", "primary")));
    when(modelAccessService.toWebsiteSummary("account-1")).thenReturn(modelAccess());

    var response = service.listLinkedAccounts(jwt());

    assertEquals(1, response.getIntegrations().size());
    assertTrue(response.getIntegrations().get(0).getCoderLinked());
    assertEquals(
        "primary", response.getIntegrations().get(0).getGcalAccounts().get(0).getAccountId());
    assertEquals(
        "alice@coder.example",
        response.getIntegrations().get(0).getLinkedAccounts().stream()
            .filter(account -> account.getType() == WebsiteLinkedIntegrationAccount.TypeEnum.CODER)
            .findFirst()
            .orElseThrow()
            .getEmail());
    assertEquals(
        WebsiteModelAccessSummary.PlanEnum.STANDARD,
        response.getIntegrations().get(0).getModelAccess().getPlan());
  }

  @Test
  void getLinkStatusUsesCanonicalAccount() {
    when(accountResolver.resolve(any(IncomingMessage.class)))
        .thenReturn(Optional.of(new AgentAccountResolver.ResolvedAccount(account(), List.of())));
    when(modelAccessService.toWebsiteSummary("account-1")).thenReturn(modelAccess());

    WebsiteAccountService.SenderLinkStatus status = service.getLinkStatus(message());

    assertTrue(status.linked());
    assertTrue(status.exactChatLinked());
    assertEquals("account-1", status.accountId());
    assertEquals(1, status.linkCount());
    assertEquals("local", status.modelAccess().getCurrentModel());
  }

  @Test
  void deleteLinkedAccountRejectsGcalKeysOutsideCurrentAccount() {
    when(accountResolver.upsertWebsiteAccount(any(Jwt.class))).thenReturn(account());

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.deleteLinkedAccount(jwt(), "gcal", "other-account::primary"));

    assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    verify(gcalClient, never()).revokeAccount(anyString());
  }

  private WebsiteModelAccessSummary modelAccess() {
    return new WebsiteModelAccessSummary()
        .accountId("account-1")
        .plan(WebsiteModelAccessSummary.PlanEnum.STANDARD)
        .isPremium(false)
        .currentModel("local")
        .currentModelLabel("Free")
        .modelSelectionAllowed(false)
        .modelSelectionConfigurable(false)
        .readOnlyReason("Free accounts use the included model.")
        .availableModels(List.of());
  }

  private Jwt jwt() {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject("sub-1")
        .claim("email", "alice@example.com")
        .claim("preferred_username", "alice")
        .build();
  }

  private IncomingMessage message() {
    return new IncomingMessage(
        "iMessage;+;chat-1",
        "msg-1",
        null,
        "link my account",
        false,
        "iMessage",
        "Alice",
        false,
        Instant.now(),
        List.of(),
        false);
  }

  private AgentAccountEntity account() {
    Instant now = Instant.now();
    AgentAccountEntity account = new AgentAccountEntity("account-1", now, now);
    account.setWebsiteSubject("sub-1");
    account.setWebsiteEmail("alice@example.com");
    account.setWebsitePreferredUsername("alice");
    return account;
  }

  private AgentAccountIdentityEntity identity() {
    Instant now = Instant.now();
    return new AgentAccountIdentityEntity(
        "identity-1",
        "account-1",
        AgentAccountIdentifiers.IMESSAGE_EMAIL,
        "alice@example.com",
        "alice@example.com",
        now,
        now);
  }

  private WebsiteAccountLinkTokenEntity tokenEntity(Instant expiresAt) {
    Instant now = Instant.now();
    return new WebsiteAccountLinkTokenEntity(
        "hash",
        "account-1",
        "iMessage;+;chat-1",
        "Alice",
        "iMessage",
        false,
        "msg-1",
        expiresAt,
        now,
        now);
  }
}

package io.breland.bbagent.server.website;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.WebsiteLinkedIntegrationAccount;
import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
import io.breland.bbagent.server.agent.AccountIdentityAliasService;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.model_picker.ModelAccessService;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountLinkTokenRepository;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountRepository;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountSenderLinkEntity;
import io.breland.bbagent.server.agent.persistence.website.WebsiteAccountSenderLinkRepository;
import io.breland.bbagent.server.agent.tools.coder.CoderMcpClient;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class WebsiteAccountServiceTest {

  private final WebsiteAccountRepository accountRepository =
      Mockito.mock(WebsiteAccountRepository.class);
  private final WebsiteAccountLinkTokenRepository tokenRepository =
      Mockito.mock(WebsiteAccountLinkTokenRepository.class);
  private final WebsiteAccountSenderLinkRepository linkRepository =
      Mockito.mock(WebsiteAccountSenderLinkRepository.class);
  private final GcalClient gcalClient = Mockito.mock(GcalClient.class);
  private final CoderMcpClient coderMcpClient = Mockito.mock(CoderMcpClient.class);
  private final ModelAccessService modelAccessService = Mockito.mock(ModelAccessService.class);
  private final AccountIdentityAliasService accountIdentityAliasService =
      Mockito.mock(AccountIdentityAliasService.class);
  private final WebsiteAccountService service =
      new WebsiteAccountService(
          accountRepository,
          tokenRepository,
          linkRepository,
          gcalClient,
          coderMcpClient,
          modelAccessService,
          accountIdentityAliasService,
          "https://chatagent.example",
          30);

  @BeforeEach
  void stubDefaultAliases() {
    when(accountIdentityAliasService.accountBaseCandidates(anyString()))
        .thenAnswer(invocation -> List.of(invocation.getArgument(0, String.class)));
    when(accountIdentityAliasService.preferredAccountBaseForWrite(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0, String.class));
  }

  @Test
  void createLinkTokenReturnsUrlAndStoresOnlyHash() {
    when(tokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    WebsiteAccountService.CreatedLinkToken link = service.createLinkToken(message());

    assertTrue(link.url().startsWith("https://chatagent.example/account/link?token="));
    assertFalse(link.url().contains("token_hash"));
    ArgumentCaptor<WebsiteAccountLinkTokenEntity> captor =
        ArgumentCaptor.forClass(WebsiteAccountLinkTokenEntity.class);
    Mockito.verify(tokenRepository).save(captor.capture());
    assertEquals("Alice", captor.getValue().getCoderAccountBase());
    assertEquals("iMessage;+;chat-1|Alice", captor.getValue().getGcalAccountBase());
    assertEquals(64, captor.getValue().getTokenHash().length());
  }

  @Test
  void redeemLinkCreatesSenderLink() {
    WebsiteAccountLinkTokenEntity tokenEntity = tokenEntity(Instant.now().plusSeconds(60));
    stubAccountUpsert();
    when(tokenRepository.findById(anyString())).thenReturn(Optional.of(tokenEntity));
    when(linkRepository
            .findAllByAccountSubjectAndCoderAccountBaseInAndGcalAccountBaseInOrderByCreatedAtDesc(
                "sub-1", List.of("Alice"), List.of("iMessage;+;chat-1|Alice")))
        .thenReturn(List.of());
    when(linkRepository.existsById(anyString())).thenReturn(false);
    when(linkRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var response = service.redeemLink(jwt(), "abc123");

    assertEquals("linked", response.getStatus());
    assertEquals("Alice", response.getLink().getCoderAccountBase());
    assertEquals("iMessage;+;chat-1|Alice", response.getLink().getGcalAccountBase());
  }

  @Test
  void redeemLinkIsIdempotentForSameAccount() {
    WebsiteAccountLinkTokenEntity tokenEntity = tokenEntity(Instant.now().plusSeconds(60));
    tokenEntity.setRedeemedAccountSubject("sub-1");
    WebsiteAccountSenderLinkEntity existing = senderLink();
    stubAccountUpsert();
    when(tokenRepository.findById(anyString())).thenReturn(Optional.of(tokenEntity));
    when(linkRepository
            .findAllByAccountSubjectAndCoderAccountBaseInAndGcalAccountBaseInOrderByCreatedAtDesc(
                "sub-1", List.of("Alice"), List.of("iMessage;+;chat-1|Alice")))
        .thenReturn(List.of(existing));
    when(linkRepository.existsById(existing.getLinkId())).thenReturn(true);
    when(linkRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var response = service.redeemLink(jwt(), "abc123");

    assertEquals("already_linked", response.getStatus());
    assertEquals(existing.getLinkId(), response.getLink().getLinkId());
  }

  @Test
  void redeemLinkRejectsExpiredToken() {
    stubAccountUpsert();
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
  void redeemLinkRejectsTokenRedeemedByDifferentAccount() {
    WebsiteAccountLinkTokenEntity tokenEntity = tokenEntity(Instant.now().plusSeconds(60));
    tokenEntity.setRedeemedAccountSubject("someone-else");
    stubAccountUpsert();
    when(tokenRepository.findById(anyString())).thenReturn(Optional.of(tokenEntity));

    try {
      service.redeemLink(jwt(), "abc123");
    } catch (ResponseStatusException e) {
      assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
      return;
    }
    throw new AssertionError("Expected conflict");
  }

  @Test
  void listLinkedAccountsAggregatesCoderAndGcalStatus() {
    stubAccountUpsert();
    when(linkRepository.findAllByAccountSubjectOrderByCreatedAtDesc("sub-1"))
        .thenReturn(List.of(senderLink()));
    when(coderMcpClient.isLinked("Alice")).thenReturn(true);
    when(coderMcpClient.findLinkedAccount("Alice"))
        .thenReturn(
            Optional.of(
                new CoderMcpClient.CoderLinkedAccount("Alice", "alice@coder.example", "alice")));
    when(gcalClient.listLinkedAccountsFor("iMessage;+;chat-1|Alice"))
        .thenReturn(
            List.of(
                new GcalClient.GcalLinkedAccount(
                    "iMessage;+;chat-1|Alice::primary", "iMessage;+;chat-1|Alice", "primary")));
    when(modelAccessService.toWebsiteSummary("Alice")).thenReturn(modelAccess());

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
  void getLinkStatusChecksCurrentSenderAndChat() {
    when(linkRepository.findAllByAccountBaseInOrderByCreatedAtDesc(List.of("Alice")))
        .thenReturn(List.of(senderLink()));
    when(linkRepository
            .findAllByAccountBaseInAndCoderAccountBaseInAndGcalAccountBaseInOrderByCreatedAtDesc(
                List.of("Alice"), List.of("Alice"), List.of("iMessage;+;chat-1|Alice")))
        .thenReturn(List.of(senderLink()));
    when(modelAccessService.toWebsiteSummary("Alice")).thenReturn(modelAccess());

    WebsiteAccountService.SenderLinkStatus status = service.getLinkStatus(message());

    assertTrue(status.linked());
    assertTrue(status.exactChatLinked());
    assertEquals("Alice", status.accountBase());
    assertEquals("iMessage;+;chat-1|Alice", status.gcalAccountBase());
    assertEquals(1, status.linkCount());
    assertEquals(1, status.exactChatLinkCount());
    assertEquals("local", status.modelAccess().getCurrentModel());
  }

  @Test
  void getLinkStatusDistinguishesSenderLinkFromExactChatLink() {
    when(linkRepository.findAllByAccountBaseInOrderByCreatedAtDesc(List.of("Alice")))
        .thenReturn(List.of(senderLink()));
    when(linkRepository
            .findAllByAccountBaseInAndCoderAccountBaseInAndGcalAccountBaseInOrderByCreatedAtDesc(
                List.of("Alice"), List.of("Alice"), List.of("iMessage;+;chat-2|Alice")))
        .thenReturn(List.of());
    when(modelAccessService.toWebsiteSummary("Alice")).thenReturn(modelAccess());

    WebsiteAccountService.SenderLinkStatus status =
        service.getLinkStatus("Alice", "iMessage;+;chat-2");

    assertTrue(status.linked());
    assertFalse(status.exactChatLinked());
    assertEquals(1, status.linkCount());
    assertEquals(0, status.exactChatLinkCount());
  }

  @Test
  void getLinkStatusTreatsPhoneAndEmailAliasesAsSameSender() {
    when(accountIdentityAliasService.accountBaseCandidates("alice@example.com"))
        .thenReturn(List.of("alice@example.com", "+15555550123"));
    when(accountIdentityAliasService.accountBaseCandidates("iMessage;+;chat-1|alice@example.com"))
        .thenReturn(
            List.of("iMessage;+;chat-1|alice@example.com", "iMessage;+;chat-1|+15555550123"));
    WebsiteAccountSenderLinkEntity phoneLink =
        new WebsiteAccountSenderLinkEntity(
            "link-phone",
            "sub-1",
            "+15555550123",
            "+15555550123",
            "iMessage;+;chat-1|+15555550123",
            "iMessage;+;chat-1",
            "+15555550123",
            "iMessage",
            false,
            "msg-1",
            "hash",
            Instant.now(),
            Instant.now());
    when(linkRepository.findAllByAccountBaseInOrderByCreatedAtDesc(
            List.of("alice@example.com", "+15555550123")))
        .thenReturn(List.of(phoneLink));
    when(linkRepository
            .findAllByAccountBaseInAndCoderAccountBaseInAndGcalAccountBaseInOrderByCreatedAtDesc(
                List.of("alice@example.com", "+15555550123"),
                List.of("alice@example.com", "+15555550123"),
                List.of("iMessage;+;chat-1|alice@example.com", "iMessage;+;chat-1|+15555550123")))
        .thenReturn(List.of(phoneLink));
    when(modelAccessService.toWebsiteSummary("alice@example.com")).thenReturn(modelAccess());

    WebsiteAccountService.SenderLinkStatus status =
        service.getLinkStatus("alice@example.com", "iMessage;+;chat-1");

    assertTrue(status.linked());
    assertTrue(status.exactChatLinked());
  }

  @Test
  void findLinkedAccountEmailReturnsEmailForCurrentExactSenderLink() {
    WebsiteAccountSenderLinkEntity link = senderLink();
    when(linkRepository
            .findAllByAccountBaseInAndCoderAccountBaseInAndGcalAccountBaseInOrderByCreatedAtDesc(
                List.of("Alice"), List.of("Alice"), List.of("iMessage;+;chat-1|Alice")))
        .thenReturn(List.of(link));
    when(accountRepository.findById("sub-1"))
        .thenReturn(
            Optional.of(
                new WebsiteAccountEntity(
                    "sub-1", "alice@example.com", "alice", "Alice", Instant.now(), Instant.now())));

    Optional<String> email = service.findLinkedAccountEmail(message());

    assertEquals(Optional.of("alice@example.com"), email);
  }

  private WebsiteModelAccessSummary modelAccess() {
    return new WebsiteModelAccessSummary()
        .accountBase("Alice")
        .plan(WebsiteModelAccessSummary.PlanEnum.STANDARD)
        .isPremium(false)
        .currentModel("local")
        .currentModelLabel("Free")
        .modelSelectionAllowed(false)
        .modelSelectionConfigurable(false)
        .readOnlyReason("Free accounts use the included model.")
        .availableModels(List.of());
  }

  private void stubAccountUpsert() {
    when(accountRepository.findById("sub-1")).thenReturn(Optional.empty());
    when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
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

  private WebsiteAccountLinkTokenEntity tokenEntity(Instant expiresAt) {
    Instant now = Instant.now();
    return new WebsiteAccountLinkTokenEntity(
        "hash",
        "Alice",
        "Alice",
        "iMessage;+;chat-1|Alice",
        "iMessage;+;chat-1",
        "Alice",
        "iMessage",
        false,
        "msg-1",
        expiresAt,
        now,
        now);
  }

  private WebsiteAccountSenderLinkEntity senderLink() {
    Instant now = Instant.now();
    return new WebsiteAccountSenderLinkEntity(
        "link-1",
        "sub-1",
        "Alice",
        "Alice",
        "iMessage;+;chat-1|Alice",
        "iMessage;+;chat-1",
        "Alice",
        "iMessage",
        false,
        "msg-1",
        "hash",
        now,
        now);
  }
}

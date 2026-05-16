package io.breland.bbagent.server.website;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.WebsiteModelAccessSummary;
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
  private final WebsiteAccountService service =
      new WebsiteAccountService(
          accountRepository,
          tokenRepository,
          linkRepository,
          gcalClient,
          coderMcpClient,
          modelAccessService,
          "https://chatagent.example",
          30);

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
    when(linkRepository.findByAccountSubjectAndCoderAccountBaseAndGcalAccountBase(
            "sub-1", "Alice", "iMessage;+;chat-1|Alice"))
        .thenReturn(Optional.empty());
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
    when(linkRepository.findByAccountSubjectAndCoderAccountBaseAndGcalAccountBase(
            "sub-1", "Alice", "iMessage;+;chat-1|Alice"))
        .thenReturn(Optional.of(existing));
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
    when(gcalClient.listAccountsFor("iMessage;+;chat-1|Alice")).thenReturn(List.of("primary"));
    when(modelAccessService.toWebsiteSummary("Alice")).thenReturn(modelAccess());

    var response = service.listLinkedAccounts(jwt());

    assertEquals(1, response.getIntegrations().size());
    assertTrue(response.getIntegrations().get(0).getCoderLinked());
    assertEquals(
        "primary", response.getIntegrations().get(0).getGcalAccounts().get(0).getAccountId());
    assertEquals(
        WebsiteModelAccessSummary.PlanEnum.STANDARD,
        response.getIntegrations().get(0).getModelAccess().getPlan());
  }

  @Test
  void getLinkStatusChecksCurrentSenderAndChat() {
    when(linkRepository.findAllByAccountBaseOrderByCreatedAtDesc("Alice"))
        .thenReturn(List.of(senderLink()));
    when(linkRepository
            .findAllByAccountBaseAndCoderAccountBaseAndGcalAccountBaseOrderByCreatedAtDesc(
                "Alice", "Alice", "iMessage;+;chat-1|Alice"))
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
    when(linkRepository.findAllByAccountBaseOrderByCreatedAtDesc("Alice"))
        .thenReturn(List.of(senderLink()));
    when(linkRepository
            .findAllByAccountBaseAndCoderAccountBaseAndGcalAccountBaseOrderByCreatedAtDesc(
                "Alice", "Alice", "iMessage;+;chat-2|Alice"))
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
  void findLinkedAccountEmailReturnsEmailForCurrentExactSenderLink() {
    WebsiteAccountSenderLinkEntity link = senderLink();
    when(linkRepository
            .findAllByAccountBaseAndCoderAccountBaseAndGcalAccountBaseOrderByCreatedAtDesc(
                "Alice", "Alice", "iMessage;+;chat-1|Alice"))
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

package io.breland.bbagent.server.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.ContactMessageRequest;
import io.breland.bbagent.server.agent.account.AgentAccountResolver;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountEntity;
import io.breland.bbagent.server.linear.LinearIssueService;
import io.breland.bbagent.server.linear.LinearIssueService.ContactIssueInput;
import io.breland.bbagent.server.linear.LinearIssueService.LinearIssue;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class ContactServiceTest {

  @Test
  void storesVerifiedContactMessage() {
    ContactProperties properties = new ContactProperties();
    CapVerificationService capVerificationService = mock(CapVerificationService.class);
    LinearIssueService linearIssueService = mock(LinearIssueService.class);
    AgentAccountResolver accountResolver = mock(AgentAccountResolver.class);
    when(capVerificationService.isConfigured()).thenReturn(true);
    when(capVerificationService.verify("cap-token")).thenReturn(true);
    when(linearIssueService.createContactIssue(any(ContactIssueInput.class)))
        .thenReturn(
            new LinearIssue(
                "issue-id",
                "BLU-123",
                "[Contact] Help",
                "https://linear.app/bluechat/issue/BLU-123/help",
                Instant.parse("2026-05-01T00:00:00Z")));
    ContactService service =
        new ContactService(properties, capVerificationService, linearIssueService, accountResolver);

    var response =
        service.createMessage(
            new ContactMessageRequest()
                .name("Ada")
                .email("ada@example.com")
                .subject("Help")
                .message("I need a hand.")
                .capToken("cap-token"),
            null,
            null);

    ArgumentCaptor<ContactIssueInput> issueCaptor =
        ArgumentCaptor.forClass(ContactIssueInput.class);
    verify(linearIssueService).createContactIssue(issueCaptor.capture());
    ContactIssueInput issue = issueCaptor.getValue();
    assertThat(response.getStatus()).isEqualTo("accepted");
    assertThat(response.getMessageId()).isEqualTo("BLU-123");
    assertThat(issue.capVerified()).isTrue();
    assertThat(issue.accountId()).isNull();
    assertThat(issue.email()).isEqualTo("ada@example.com");
    assertThat(issue.message()).isEqualTo("I need a hand.");
  }

  @Test
  void includesAccountIdForAuthenticatedWebsiteUser() {
    ContactProperties properties = new ContactProperties();
    CapVerificationService capVerificationService = mock(CapVerificationService.class);
    LinearIssueService linearIssueService = mock(LinearIssueService.class);
    AgentAccountResolver accountResolver = mock(AgentAccountResolver.class);
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("sub-1")
            .claim("email", "ada@example.com")
            .build();
    when(capVerificationService.isConfigured()).thenReturn(true);
    when(capVerificationService.verify("cap-token")).thenReturn(true);
    when(accountResolver.upsertWebsiteAccount(jwt))
        .thenReturn(
            new AgentAccountEntity(
                "account-1", Instant.parse("2026-05-01T00:00:00Z"), Instant.now()));
    when(linearIssueService.createContactIssue(any(ContactIssueInput.class)))
        .thenReturn(
            new LinearIssue(
                "issue-id",
                "BLU-124",
                "[Contact] Help",
                "https://linear.app/bluechat/issue/BLU-124/help",
                Instant.parse("2026-05-01T00:00:00Z")));
    ContactService service =
        new ContactService(properties, capVerificationService, linearIssueService, accountResolver);

    service.createMessage(
        new ContactMessageRequest()
            .name("Ada")
            .email("ada@example.com")
            .subject("Help")
            .message("I need a hand.")
            .capToken("cap-token"),
        null,
        jwt);

    ArgumentCaptor<ContactIssueInput> issueCaptor =
        ArgumentCaptor.forClass(ContactIssueInput.class);
    verify(linearIssueService).createContactIssue(issueCaptor.capture());
    assertThat(issueCaptor.getValue().accountId()).isEqualTo("account-1");
  }

  @Test
  void stillCreatesContactIssueWhenAuthenticatedAccountResolutionFails() {
    ContactProperties properties = new ContactProperties();
    CapVerificationService capVerificationService = mock(CapVerificationService.class);
    LinearIssueService linearIssueService = mock(LinearIssueService.class);
    AgentAccountResolver accountResolver = mock(AgentAccountResolver.class);
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("sub-1")
            .claim("email", "ada@example.com")
            .build();
    when(capVerificationService.isConfigured()).thenReturn(true);
    when(capVerificationService.verify("cap-token")).thenReturn(true);
    when(accountResolver.upsertWebsiteAccount(jwt)).thenThrow(new IllegalStateException("offline"));
    when(linearIssueService.createContactIssue(any(ContactIssueInput.class)))
        .thenReturn(
            new LinearIssue(
                "issue-id",
                "BLU-125",
                "[Contact] Help",
                "https://linear.app/bluechat/issue/BLU-125/help",
                Instant.parse("2026-05-01T00:00:00Z")));
    ContactService service =
        new ContactService(properties, capVerificationService, linearIssueService, accountResolver);

    var response =
        service.createMessage(
            new ContactMessageRequest()
                .name("Ada")
                .email("ada@example.com")
                .subject("Help")
                .message("I need a hand.")
                .capToken("cap-token"),
            null,
            jwt);

    ArgumentCaptor<ContactIssueInput> issueCaptor =
        ArgumentCaptor.forClass(ContactIssueInput.class);
    verify(linearIssueService).createContactIssue(issueCaptor.capture());
    assertThat(response.getMessageId()).isEqualTo("BLU-125");
    assertThat(issueCaptor.getValue().accountId()).isNull();
  }

  @Test
  void rejectsContactMessageWhenCapIsRequiredAndMissing() {
    ContactProperties properties = new ContactProperties();
    CapVerificationService capVerificationService = mock(CapVerificationService.class);
    LinearIssueService linearIssueService = mock(LinearIssueService.class);
    AgentAccountResolver accountResolver = mock(AgentAccountResolver.class);
    when(capVerificationService.isConfigured()).thenReturn(true);
    ContactService service =
        new ContactService(properties, capVerificationService, linearIssueService, accountResolver);

    assertThatThrownBy(
            () ->
                service.createMessage(
                    new ContactMessageRequest()
                        .name("Ada")
                        .email("ada@example.com")
                        .subject("Help")
                        .message("I need a hand.")
                        .capToken(" "),
                    null,
                    null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("CAPTCHA verification failed");
    verify(linearIssueService, never()).createContactIssue(any());
  }
}

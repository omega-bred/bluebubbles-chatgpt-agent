package io.breland.bbagent.server.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.ContactMessageRequest;
import io.breland.bbagent.server.linear.LinearIssueService;
import io.breland.bbagent.server.linear.LinearIssueService.ContactIssueInput;
import io.breland.bbagent.server.linear.LinearIssueService.LinearIssue;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class ContactServiceTest {

  @Test
  void storesVerifiedContactMessage() {
    ContactProperties properties = new ContactProperties();
    CapVerificationService capVerificationService = mock(CapVerificationService.class);
    LinearIssueService linearIssueService = mock(LinearIssueService.class);
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
        new ContactService(properties, capVerificationService, linearIssueService);

    var response =
        service.createMessage(
            new ContactMessageRequest()
                .name("Ada")
                .email("ada@example.com")
                .subject("Help")
                .message("I need a hand.")
                .capToken("cap-token"),
            null);

    ArgumentCaptor<ContactIssueInput> issueCaptor =
        ArgumentCaptor.forClass(ContactIssueInput.class);
    verify(linearIssueService).createContactIssue(issueCaptor.capture());
    ContactIssueInput issue = issueCaptor.getValue();
    assertThat(response.getStatus()).isEqualTo("accepted");
    assertThat(response.getMessageId()).isEqualTo("BLU-123");
    assertThat(issue.capVerified()).isTrue();
    assertThat(issue.email()).isEqualTo("ada@example.com");
    assertThat(issue.message()).isEqualTo("I need a hand.");
  }

  @Test
  void rejectsContactMessageWhenCapIsRequiredAndMissing() {
    ContactProperties properties = new ContactProperties();
    CapVerificationService capVerificationService = mock(CapVerificationService.class);
    LinearIssueService linearIssueService = mock(LinearIssueService.class);
    when(capVerificationService.isConfigured()).thenReturn(true);
    ContactService service =
        new ContactService(properties, capVerificationService, linearIssueService);

    assertThatThrownBy(
            () ->
                service.createMessage(
                    new ContactMessageRequest()
                        .name("Ada")
                        .email("ada@example.com")
                        .subject("Help")
                        .message("I need a hand.")
                        .capToken(" "),
                    null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("CAPTCHA verification failed");
    verify(linearIssueService, never()).createContactIssue(any());
  }
}

package io.breland.bbagent.server.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.breland.bbagent.generated.model.ContactMessageRequest;
import io.breland.bbagent.server.agent.persistence.contact.WebsiteContactMessageEntity;
import io.breland.bbagent.server.agent.persistence.contact.WebsiteContactMessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class ContactServiceTest {

  @Test
  void storesVerifiedContactMessage() {
    ContactProperties properties = new ContactProperties();
    CapVerificationService capVerificationService = mock(CapVerificationService.class);
    WebsiteContactMessageRepository repository = mock(WebsiteContactMessageRepository.class);
    when(capVerificationService.isConfigured()).thenReturn(true);
    when(capVerificationService.verify("cap-token")).thenReturn(true);
    when(repository.save(any(WebsiteContactMessageEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ContactService service = new ContactService(properties, capVerificationService, repository);

    var response =
        service.createMessage(
            new ContactMessageRequest()
                .name("Ada")
                .email("ada@example.com")
                .subject("Help")
                .message("I need a hand.")
                .capToken("cap-token"),
            null);

    ArgumentCaptor<WebsiteContactMessageEntity> entityCaptor =
        ArgumentCaptor.forClass(WebsiteContactMessageEntity.class);
    verify(repository).save(entityCaptor.capture());
    WebsiteContactMessageEntity entity = entityCaptor.getValue();
    assertThat(response.getStatus()).isEqualTo("accepted");
    assertThat(response.getMessageId()).isEqualTo(entity.getMessageId());
    assertThat(entity.isCapVerified()).isTrue();
    assertThat(entity.getStatus()).isEqualTo("unread");
    assertThat(entity.getEmail()).isEqualTo("ada@example.com");
  }

  @Test
  void rejectsContactMessageWhenCapIsRequiredAndMissing() {
    ContactProperties properties = new ContactProperties();
    CapVerificationService capVerificationService = mock(CapVerificationService.class);
    WebsiteContactMessageRepository repository = mock(WebsiteContactMessageRepository.class);
    when(capVerificationService.isConfigured()).thenReturn(true);
    ContactService service = new ContactService(properties, capVerificationService, repository);

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
  }
}

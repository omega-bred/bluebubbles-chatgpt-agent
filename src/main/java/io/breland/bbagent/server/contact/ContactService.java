package io.breland.bbagent.server.contact;

import io.breland.bbagent.generated.model.ContactConfigResponse;
import io.breland.bbagent.generated.model.ContactMessageRequest;
import io.breland.bbagent.generated.model.ContactMessageResponse;
import io.breland.bbagent.server.agent.persistence.contact.WebsiteContactMessageEntity;
import io.breland.bbagent.server.agent.persistence.contact.WebsiteContactMessageRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
public class ContactService {
  private static final int MAX_NAME_LENGTH = 200;
  private static final int MAX_EMAIL_LENGTH = 320;
  private static final int MAX_SUBJECT_LENGTH = 200;
  private static final int MAX_MESSAGE_LENGTH = 5000;

  private final ContactProperties properties;
  private final CapVerificationService capVerificationService;
  private final WebsiteContactMessageRepository repository;

  public ContactService(
      ContactProperties properties,
      CapVerificationService capVerificationService,
      WebsiteContactMessageRepository repository) {
    this.properties = properties;
    this.capVerificationService = capVerificationService;
    this.repository = repository;
  }

  public ContactConfigResponse config() {
    return new ContactConfigResponse()
        .enabled(properties.isEnabled())
        .captchaRequired(properties.isCapRequired())
        .captchaConfigured(capVerificationService.isConfigured())
        .capApiEndpoint(capVerificationService.apiEndpoint());
  }

  @Transactional
  public ContactMessageResponse createMessage(
      ContactMessageRequest request, HttpServletRequest servletRequest) {
    if (!properties.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Contact form is disabled");
    }
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contact request is required");
    }
    boolean capVerified = verifyCap(request.getCapToken());
    Instant now = Instant.now();
    WebsiteContactMessageEntity entity =
        repository.save(
            new WebsiteContactMessageEntity(
                UUID.randomUUID().toString(),
                now,
                requireText(request.getName(), "name", MAX_NAME_LENGTH),
                requireText(request.getEmail(), "email", MAX_EMAIL_LENGTH),
                requireText(request.getSubject(), "subject", MAX_SUBJECT_LENGTH),
                requireText(request.getMessage(), "message", MAX_MESSAGE_LENGTH),
                "unread",
                remoteAddress(servletRequest),
                truncate(
                    servletRequest == null ? null : servletRequest.getHeader("User-Agent"), 512),
                capVerified,
                now,
                now));
    log.info("Stored contact message id={} email={}", entity.getMessageId(), entity.getEmail());
    return new ContactMessageResponse().status("accepted").messageId(entity.getMessageId());
  }

  private boolean verifyCap(String token) {
    if (!properties.isCapRequired()) {
      return false;
    }
    if (!capVerificationService.isConfigured()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Contact CAPTCHA is not configured");
    }
    if (!capVerificationService.verify(token)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CAPTCHA verification failed");
    }
    return true;
  }

  private String requireText(String value, String fieldName, int maxLength) {
    String trimmed = StringUtils.trimToNull(value);
    if (trimmed == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
    }
    return truncate(trimmed, maxLength);
  }

  private String truncate(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
  }

  private String remoteAddress(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String forwardedFor = StringUtils.trimToNull(request.getHeader("X-Forwarded-For"));
    if (forwardedFor != null) {
      return truncate(forwardedFor.split(",", 2)[0], 255);
    }
    return truncate(request.getRemoteAddr(), 255);
  }
}

package io.breland.bbagent.server.contact;

import io.breland.bbagent.generated.model.ContactConfigResponse;
import io.breland.bbagent.generated.model.ContactMessageRequest;
import io.breland.bbagent.generated.model.ContactMessageResponse;
import io.breland.bbagent.server.agent.AgentAccountResolver;
import io.breland.bbagent.server.linear.LinearIssueService;
import io.breland.bbagent.server.linear.LinearIssueService.ContactIssueInput;
import io.breland.bbagent.server.linear.LinearIssueService.LinearIssue;
import io.breland.bbagent.server.linear.LinearIssueService.LinearIssueException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
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
  private final LinearIssueService linearIssueService;
  private final AgentAccountResolver accountResolver;

  public ContactService(
      ContactProperties properties,
      CapVerificationService capVerificationService,
      LinearIssueService linearIssueService,
      AgentAccountResolver accountResolver) {
    this.properties = properties;
    this.capVerificationService = capVerificationService;
    this.linearIssueService = linearIssueService;
    this.accountResolver = accountResolver;
  }

  public ContactConfigResponse config() {
    return new ContactConfigResponse()
        .enabled(properties.isEnabled())
        .captchaRequired(properties.isCapRequired())
        .captchaConfigured(capVerificationService.isConfigured())
        .capApiEndpoint(capVerificationService.apiEndpoint());
  }

  public ContactMessageResponse createMessage(
      ContactMessageRequest request, HttpServletRequest servletRequest, @Nullable Jwt jwt) {
    if (!properties.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Contact form is disabled");
    }
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contact request is required");
    }
    boolean capVerified = verifyCap(request.getCapToken());
    Instant now = Instant.now();
    String name = requireText(request.getName(), "name", MAX_NAME_LENGTH);
    String email = requireText(request.getEmail(), "email", MAX_EMAIL_LENGTH);
    String subject = requireText(request.getSubject(), "subject", MAX_SUBJECT_LENGTH);
    String message = requireText(request.getMessage(), "message", MAX_MESSAGE_LENGTH);
    String accountId = resolveAccountId(jwt);
    try {
      LinearIssue issue =
          linearIssueService.createContactIssue(
              new ContactIssueInput(
                  accountId,
                  name,
                  email,
                  subject,
                  message,
                  remoteAddress(servletRequest),
                  trimAndTruncate(
                      servletRequest == null ? null : servletRequest.getHeader("User-Agent"), 512),
                  capVerified,
                  now));
      log.info("Created Linear contact issue reference={} email={}", issue.reference(), email);
      return new ContactMessageResponse().status("accepted").messageId(issue.reference());
    } catch (LinearIssueException e) {
      log.warn("Could not create Linear contact issue: {}", e.getMessage());
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Contact issue tracker is not configured");
    }
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
    return StringUtils.truncate(trimmed, maxLength);
  }

  private String trimAndTruncate(String value, int maxLength) {
    return StringUtils.truncate(StringUtils.trim(value), maxLength);
  }

  private String remoteAddress(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String forwardedFor = StringUtils.trimToNull(request.getHeader("X-Forwarded-For"));
    if (forwardedFor != null) {
      return trimAndTruncate(forwardedFor.split(",", 2)[0], 255);
    }
    return trimAndTruncate(request.getRemoteAddr(), 255);
  }

  private String resolveAccountId(@Nullable Jwt jwt) {
    if (jwt == null) {
      return null;
    }
    try {
      return accountResolver.upsertWebsiteAccount(jwt).getAccountId();
    } catch (RuntimeException e) {
      log.warn("Could not resolve website account for contact request: {}", e.getMessage());
      return null;
    }
  }
}

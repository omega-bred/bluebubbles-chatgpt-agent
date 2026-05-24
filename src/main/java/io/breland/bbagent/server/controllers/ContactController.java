package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.model.ContactConfigResponse;
import io.breland.bbagent.generated.model.ContactMessageRequest;
import io.breland.bbagent.generated.model.ContactMessageResponse;
import io.breland.bbagent.server.contact.ContactService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class ContactController {
  private final ContactService contactService;

  public ContactController(ContactService contactService) {
    this.contactService = contactService;
  }

  @GetMapping(
      path = "/api/v1/contact/get.contactConfig",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ContactConfigResponse> contactGetConfig() {
    return ResponseEntity.ok(contactService.config());
  }

  @PostMapping(
      path = "/api/v1/contact/create.contactMessages",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ContactMessageResponse> contactCreateMessage(
      @RequestBody ContactMessageRequest request,
      HttpServletRequest servletRequest,
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(contactService.createMessage(request, servletRequest, jwt));
  }
}

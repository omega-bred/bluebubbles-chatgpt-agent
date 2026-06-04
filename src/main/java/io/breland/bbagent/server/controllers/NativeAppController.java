package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.model.NativeAppSessionCreateRequest;
import io.breland.bbagent.generated.model.NativeAppSessionResponse;
import io.breland.bbagent.server.nativeapp.NativeAppSessionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class NativeAppController {
  private final NativeAppSessionService sessionService;

  public NativeAppController(NativeAppSessionService sessionService) {
    this.sessionService = sessionService;
  }

  @PostMapping(
      path = "/api/v1/nativeApp/createSession.nativeAppSessions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<NativeAppSessionResponse> nativeAppCreateSession(
      @RequestBody(required = false) NativeAppSessionCreateRequest request) {
    return ResponseEntity.ok(sessionService.createSession(request));
  }

  @GetMapping(
      path = "/api/v1/nativeApp/get.nativeAppSession",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<NativeAppSessionResponse> nativeAppGetSession(
      @RequestHeader(NativeAppSessionService.SESSION_HEADER) String sessionToken,
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(
        sessionService.getSession(
            sessionToken,
            jwt.getClaimAsString(NativeAppSessionService.NATIVE_APP_ACCOUNT_ID_CLAIM)));
  }
}

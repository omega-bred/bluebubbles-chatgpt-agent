package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.model.AppClipCreateSessionRequest;
import io.breland.bbagent.generated.model.AppClipEventRequest;
import io.breland.bbagent.generated.model.AppClipEventResponse;
import io.breland.bbagent.generated.model.AppClipSessionResponse;
import io.breland.bbagent.server.appclip.AppClipEventService;
import io.breland.bbagent.server.appclip.AppClipSessionAuthenticationFilter;
import io.breland.bbagent.server.appclip.AppClipSessionService;
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
public class AppClipController {
  private final AppClipSessionService sessionService;
  private final AppClipEventService eventService;

  public AppClipController(AppClipSessionService sessionService, AppClipEventService eventService) {
    this.sessionService = sessionService;
    this.eventService = eventService;
  }

  @PostMapping(
      path = "/api/v1/appClip/createSession.appClipSessions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AppClipSessionResponse> appClipCreateSession(
      @RequestBody AppClipCreateSessionRequest request) {
    return ResponseEntity.ok(sessionService.createSession(request.getLinkToken()));
  }

  @GetMapping(
      path = "/api/v1/appClip/get.appClipSession",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AppClipSessionResponse> appClipGetSession(
      @RequestHeader(AppClipSessionAuthenticationFilter.SESSION_HEADER) String sessionToken,
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(
        sessionService.getSession(
            sessionToken, jwt.getClaimAsString(AppClipSessionService.APP_CLIP_ACCOUNT_ID_CLAIM)));
  }

  @PostMapping(
      path = "/api/v1/appClip/createEvent.appClipEvents",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AppClipEventResponse> appClipCreateEvent(
      @RequestBody AppClipEventRequest request, @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(
        eventService.track(
            jwt.getClaimAsString(AppClipSessionService.APP_CLIP_ACCOUNT_ID_CLAIM), request));
  }

  @PostMapping(
      path = "/api/v1/appClip/createBootstrapEvent.appClipEvents",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AppClipEventResponse> appClipCreateBootstrapEvent(
      @RequestBody AppClipEventRequest request) {
    return ResponseEntity.ok(eventService.trackBootstrap(request));
  }
}

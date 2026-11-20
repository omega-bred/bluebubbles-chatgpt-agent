package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.model.AppClipCreateSessionRequest;
import io.breland.bbagent.generated.model.AppClipEventRequest;
import io.breland.bbagent.generated.model.AppClipEventResponse;
import io.breland.bbagent.generated.model.AppClipSessionResponse;
import io.breland.bbagent.server.appclip.AppClipEventService;
import io.breland.bbagent.server.appclip.AppClipSessionAuthenticationFilter;
import io.breland.bbagent.server.appclip.AppClipSessionService;
import io.breland.bbagent.server.appclip.AppClipSessionService.AuthenticatedAppClipSession;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(sessionService.createSession(request.getLinkToken()));
  }

  @GetMapping(
      path = "/api/v1/appClip/get.appClipSession",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AppClipSessionResponse> appClipGetSession(
      @RequestAttribute(
              name = AppClipSessionAuthenticationFilter.SESSION_REQUEST_ATTRIBUTE,
              required = false)
          AuthenticatedAppClipSession session) {
    if (session == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid App Clip session");
    }
    return ResponseEntity.ok(sessionService.getSession(session));
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

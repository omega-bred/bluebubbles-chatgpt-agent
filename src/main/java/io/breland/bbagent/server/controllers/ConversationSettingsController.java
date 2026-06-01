package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.model.ConversationSettingsResponse;
import io.breland.bbagent.generated.model.ConversationSettingsUpdateRequest;
import io.breland.bbagent.generated.model.ConversationSettingsUpdateResponse;
import io.breland.bbagent.server.appclip.AppClipSessionService;
import io.breland.bbagent.server.conversation.ConversationSettingsService;
import io.breland.bbagent.server.website.WebsiteAccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class ConversationSettingsController {
  private final ConversationSettingsService settingsService;

  public ConversationSettingsController(ConversationSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @GetMapping(
      path = "/api/v1/conversationSettings/get.conversationSettings",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ConversationSettingsResponse> conversationSettingsGet(
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(settingsService.getSettings(accountId(jwt), chatGuid(jwt)));
  }

  @PostMapping(
      path = "/api/v1/conversationSettings/updateResponsiveness.conversationSettings",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ConversationSettingsUpdateResponse>
      conversationSettingsUpdateResponsiveness(
          @RequestBody ConversationSettingsUpdateRequest request,
          @AuthenticationPrincipal Jwt jwt) {
    String responsiveness =
        request.getResponsiveness() == null ? null : request.getResponsiveness().getValue();
    return ResponseEntity.ok(
        settingsService.updateResponsiveness(accountId(jwt), chatGuid(jwt), responsiveness));
  }

  private String accountId(Jwt jwt) {
    requireConversationSettingsSession(jwt);
    return jwt.getClaimAsString(AppClipSessionService.APP_CLIP_ACCOUNT_ID_CLAIM);
  }

  private String chatGuid(Jwt jwt) {
    requireConversationSettingsSession(jwt);
    String chatGuid = jwt.getClaimAsString(AppClipSessionService.APP_CLIP_CHAT_GUID_CLAIM);
    if (chatGuid == null || chatGuid.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing conversation");
    }
    return chatGuid;
  }

  private void requireConversationSettingsSession(Jwt jwt) {
    if (jwt == null
        || !WebsiteAccountService.LINK_PURPOSE_CONVERSATION_SETTINGS.equals(
            jwt.getClaimAsString(AppClipSessionService.APP_CLIP_PURPOSE_CLAIM))) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Conversation settings link required");
    }
  }
}

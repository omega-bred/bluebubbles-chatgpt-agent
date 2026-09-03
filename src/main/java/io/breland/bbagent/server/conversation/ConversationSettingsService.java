package io.breland.bbagent.server.conversation;

import static io.breland.bbagent.server.StringSupport.firstNonBlank;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.generated.model.ConversationGroupMemorySetting;
import io.breland.bbagent.generated.model.ConversationParticipantSummary;
import io.breland.bbagent.generated.model.ConversationPersonalCatchupSetting;
import io.breland.bbagent.generated.model.ConversationResponsivenessOption;
import io.breland.bbagent.generated.model.ConversationSettingsResponse;
import io.breland.bbagent.generated.model.ConversationSettingsUpdateResponse;
import io.breland.bbagent.generated.model.ConversationSummary;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupPreferenceSetting;
import io.breland.bbagent.server.agent.memory.ConversationMemorySettingsService;
import io.breland.bbagent.server.agent.memory.ConversationMemorySettingsService.GroupMemorySetting;
import io.breland.bbagent.server.agent.memory.ProactiveCatchupService;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.analytics.UmamiAnalyticsService;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSettingsService {
  private static final List<ResponsivenessOption> OPTIONS =
      List.of(
          new ResponsivenessOption(
              AssistantResponsiveness.SILENT,
              "Silent",
              "Only reply when the message starts with Chat."),
          new ResponsivenessOption(
              AssistantResponsiveness.LESS_RESPONSIVE,
              "Conservative",
              "Reply only when directly addressed or clearly needed."),
          new ResponsivenessOption(
              AssistantResponsiveness.DEFAULT,
              "Balanced",
              "Use the normal BlueChatAI response behavior."),
          new ResponsivenessOption(
              AssistantResponsiveness.MORE_RESPONSIVE,
              "Active",
              "Participate more often when helpful."));

  private final AgentProfileService profileService;
  private final BBHttpClientWrapper bbHttpClientWrapper;
  private final @Nullable UmamiAnalyticsService umamiAnalyticsService;
  private final @Nullable ConversationMemorySettingsService memorySettingsService;
  private final @Nullable ProactiveCatchupService proactiveCatchupService;

  public ConversationSettingsResponse getSettings(@Nullable String accountId, String chatGuid) {
    String cleanChatGuid = requireChatGuid(chatGuid);
    return response(accountId, cleanChatGuid);
  }

  public ConversationSettingsUpdateResponse updateResponsiveness(
      String accountId, String chatGuid, String responsiveness) {
    String cleanChatGuid = requireChatGuid(chatGuid);
    AssistantResponsiveness resolved = parseResponsiveness(responsiveness);
    profileService.setAssistantResponsiveness(cleanChatGuid, resolved);
    trackUpdate(accountId, resolved);
    ConversationSettingsResponse settings = response(accountId, cleanChatGuid);
    return new ConversationSettingsUpdateResponse()
        .settings(settings)
        .message("Conversation response style changed to " + labelFor(resolved) + ".");
  }

  public ConversationSettingsUpdateResponse updateGroupMemory(
      String accountId, String chatGuid, boolean enabled) {
    String cleanChatGuid = requireChatGuid(chatGuid);
    if (memorySettingsService == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Memory settings unavailable");
    }
    GroupMemorySetting current = memorySettingsService.getGroupMemory(cleanChatGuid);
    if (!current.available()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Group memory is unavailable for this conversation");
    }
    try {
      memorySettingsService.updateGroupMemory(accountId, cleanChatGuid, enabled);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
    }
    return new ConversationSettingsUpdateResponse()
        .settings(response(accountId, cleanChatGuid))
        .message("Group memory " + (enabled ? "enabled" : "disabled") + ".");
  }

  public ConversationSettingsUpdateResponse updateCatchups(
      String accountId,
      String chatGuid,
      boolean enabled,
      String timezone,
      String quietStart,
      String quietEnd) {
    String cleanChatGuid = requireChatGuid(chatGuid);
    if (proactiveCatchupService == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Personal catch-ups unavailable");
    }
    try {
      proactiveCatchupService.updateForChat(
          accountId, cleanChatGuid, enabled, timezone, quietStart, quietEnd);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
    return new ConversationSettingsUpdateResponse()
        .settings(response(accountId, cleanChatGuid))
        .message(
            enabled
                ? "Personal catch-ups enabled for developments since your last catch-up."
                : "Personal catch-ups disabled.");
  }

  private ConversationSettingsResponse response(@Nullable String accountId, String chatGuid) {
    AssistantResponsiveness current = profileService.getAssistantResponsiveness(chatGuid);
    return new ConversationSettingsResponse()
        .conversation(conversationSummary(chatGuid))
        .currentResponsiveness(toResponseEnum(current))
        .currentResponsivenessLabel(labelFor(current))
        .options(OPTIONS.stream().map(this::toOption).toList())
        .groupMemory(groupMemorySetting(chatGuid))
        .personalCatchups(personalCatchupSetting(accountId, chatGuid));
  }

  private ConversationGroupMemorySetting groupMemorySetting(String chatGuid) {
    GroupMemorySetting setting =
        memorySettingsService == null
            ? new GroupMemorySetting(
                false, false, "Memory", "Group memory is unavailable for this conversation.", null)
            : memorySettingsService.getGroupMemory(chatGuid);
    ConversationGroupMemorySetting response =
        new ConversationGroupMemorySetting()
            .available(setting.available())
            .enabled(setting.enabled())
            .label(setting.label())
            .description(setting.description());
    if (setting.collectionStartedAt() != null) {
      response.collectionStartedAt(setting.collectionStartedAt().atOffset(ZoneOffset.UTC));
    }
    return response;
  }

  private ConversationPersonalCatchupSetting personalCatchupSetting(
      @Nullable String accountId, String chatGuid) {
    CatchupPreferenceSetting setting =
        proactiveCatchupService == null || StringUtils.isBlank(accountId)
            ? new CatchupPreferenceSetting(false, false, "UTC", "22:00", "08:00", null, null)
            : proactiveCatchupService.preferenceForChat(accountId, chatGuid);
    ConversationPersonalCatchupSetting response =
        new ConversationPersonalCatchupSetting()
            .available(setting.available())
            .enabled(setting.enabled())
            .timezone(setting.timezone())
            .quietStart(setting.quietStart())
            .quietEnd(setting.quietEnd());
    if (setting.nextDeliveryAt() != null) {
      response.nextDeliveryAt(setting.nextDeliveryAt().atOffset(ZoneOffset.UTC));
    }
    return response;
  }

  private ConversationSummary conversationSummary(String chatGuid) {
    JsonNode data = null;
    try {
      data = bbHttpClientWrapper.getConversationInfoJson(chatGuid);
    } catch (RuntimeException e) {
      log.debug("Failed to load conversation metadata for settings chat={}", chatGuid, e);
    }
    ConversationSummary summary =
        new ConversationSummary()
            .chatGuid(chatGuid)
            .displayName(firstNonBlank(text(data, "displayName"), text(data, "chatIdentifier")))
            .chatIdentifier(text(data, "chatIdentifier"))
            .isGroup(resolveGroup(data))
            .participants(participants(data))
            .iconUrl(
                firstNonBlank(text(data, "icon"), text(data, "groupIcon"), text(data, "avatar")));
    summary.participantCount(summary.getParticipants().size());
    if (StringUtils.isBlank(summary.getDisplayName())) {
      summary.displayName("BlueChat conversation");
    }
    return summary;
  }

  private List<ConversationParticipantSummary> participants(@Nullable JsonNode data) {
    JsonNode participantNodes = data == null ? null : data.get("participants");
    if (participantNodes == null || !participantNodes.isArray()) {
      return List.of();
    }
    List<ConversationParticipantSummary> participants = new ArrayList<>();
    for (JsonNode participant : participantNodes) {
      String address = firstNonBlank(text(participant, "address"), text(participant, "handle"));
      if (StringUtils.isBlank(address)) {
        continue;
      }
      participants.add(
          new ConversationParticipantSummary()
              .address(address)
              .country(text(participant, "country")));
    }
    return participants;
  }

  private Boolean resolveGroup(@Nullable JsonNode data) {
    if (data == null) {
      return null;
    }
    JsonNode value = firstNode(data, "isGroup", "group", "hasGroupName");
    return value == null || value.isNull() ? null : value.asBoolean();
  }

  private ConversationResponsivenessOption toOption(ResponsivenessOption option) {
    return new ConversationResponsivenessOption()
        .responsiveness(toOptionEnum(option.responsiveness()))
        .label(option.label())
        .description(option.description())
        .enabled(true);
  }

  private AssistantResponsiveness parseResponsiveness(String value) {
    String clean = StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
    return switch (clean) {
      case "silent" -> AssistantResponsiveness.SILENT;
      case "less_responsive" -> AssistantResponsiveness.LESS_RESPONSIVE;
      case "more_responsive" -> AssistantResponsiveness.MORE_RESPONSIVE;
      case "default" -> AssistantResponsiveness.DEFAULT;
      default ->
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown responsiveness");
    };
  }

  private String labelFor(AssistantResponsiveness responsiveness) {
    return OPTIONS.stream()
        .filter(option -> option.responsiveness() == responsiveness)
        .map(ResponsivenessOption::label)
        .findFirst()
        .orElse("Balanced");
  }

  private ConversationSettingsResponse.CurrentResponsivenessEnum toResponseEnum(
      AssistantResponsiveness responsiveness) {
    return ConversationSettingsResponse.CurrentResponsivenessEnum.fromValue(
        toWireValue(responsiveness));
  }

  private ConversationResponsivenessOption.ResponsivenessEnum toOptionEnum(
      AssistantResponsiveness responsiveness) {
    return ConversationResponsivenessOption.ResponsivenessEnum.fromValue(
        toWireValue(responsiveness));
  }

  private String toWireValue(AssistantResponsiveness responsiveness) {
    return switch (responsiveness) {
      case SILENT -> "silent";
      case LESS_RESPONSIVE -> "less_responsive";
      case MORE_RESPONSIVE -> "more_responsive";
      case DEFAULT -> "default";
    };
  }

  private String requireChatGuid(String chatGuid) {
    String clean = StringUtils.defaultIfBlank(chatGuid, null);
    if (clean == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing conversation");
    }
    return clean;
  }

  private void trackUpdate(String accountId, AssistantResponsiveness responsiveness) {
    if (umamiAnalyticsService == null || StringUtils.isBlank(accountId)) {
      return;
    }
    umamiAnalyticsService.track(
        "conversation_settings_responsiveness_updated",
        "/server/conversation/settings",
        accountId,
        Map.of("responsiveness", toWireValue(responsiveness)));
  }

  private String text(@Nullable JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private JsonNode firstNode(JsonNode node, String... fields) {
    for (String field : fields) {
      JsonNode value = node.get(field);
      if (value != null && !value.isNull()) {
        return value;
      }
    }
    return null;
  }

  private record ResponsivenessOption(
      AssistantResponsiveness responsiveness, String label, String description) {}
}

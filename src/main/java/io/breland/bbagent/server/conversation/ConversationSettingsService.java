package io.breland.bbagent.server.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.generated.model.ConversationParticipantSummary;
import io.breland.bbagent.generated.model.ConversationResponsivenessOption;
import io.breland.bbagent.generated.model.ConversationSettingsResponse;
import io.breland.bbagent.generated.model.ConversationSettingsUpdateResponse;
import io.breland.bbagent.generated.model.ConversationSummary;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.analytics.UmamiAnalyticsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
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

  public ConversationSettingsService(
      AgentProfileService profileService,
      BBHttpClientWrapper bbHttpClientWrapper,
      @Nullable UmamiAnalyticsService umamiAnalyticsService) {
    this.profileService = profileService;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.umamiAnalyticsService = umamiAnalyticsService;
  }

  public ConversationSettingsResponse getSettings(String accountId, String chatGuid) {
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

  private ConversationSettingsResponse response(String accountId, String chatGuid) {
    AssistantResponsiveness current = profileService.getAssistantResponsiveness(chatGuid);
    return new ConversationSettingsResponse()
        .conversation(conversationSummary(chatGuid))
        .currentResponsiveness(toResponseEnum(current))
        .currentResponsivenessLabel(labelFor(current))
        .options(OPTIONS.stream().map(this::toOption).toList());
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

  private String firstNonBlank(String... values) {
    return StringUtils.trimToNull(StringUtils.firstNonBlank(values));
  }

  private record ResponsivenessOption(
      AssistantResponsiveness responsiveness, String label, String description) {}
}

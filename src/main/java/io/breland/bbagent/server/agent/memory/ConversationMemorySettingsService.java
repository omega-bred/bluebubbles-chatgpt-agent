package io.breland.bbagent.server.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.ConversationRecord;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class ConversationMemorySettingsService {
  private static final String ENABLE_NOTICE =
      "Group memory is now on. I’ll remember new collective decisions from this point forward. "
          + "Earlier messages won’t be collected.";
  private static final String DISABLE_NOTICE =
      "Group memory is now off. I won’t retain new messages or collective decisions from this group.";

  private final ConversationMemoryStore store;
  private final BBHttpClientWrapper bbHttpClientWrapper;
  private final Clock clock;
  private final boolean globallyEnabled;

  @Autowired
  public ConversationMemorySettingsService(
      ConversationMemoryStore store,
      BBHttpClientWrapper bbHttpClientWrapper,
      @Nullable Clock clock,
      @Value("${bbagent.memory.group.enabled:false}") boolean globallyEnabled) {
    this.store = store;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.globallyEnabled = globallyEnabled;
  }

  public ConversationMemorySettingsService(
      ConversationMemoryStore store,
      BBHttpClientWrapper bbHttpClientWrapper,
      @Nullable Clock clock) {
    this(store, bbHttpClientWrapper, clock, true);
  }

  public GroupMemorySetting getGroupMemory(String chatGuid) {
    Optional<ConversationRecord> conversation = findOrRegisterConversation(chatGuid);
    if (conversation.isEmpty() || !conversation.get().group()) {
      return unavailableSetting();
    }
    if (!globallyEnabled) {
      return globallyDisabledSetting();
    }
    return toSetting(conversation.get());
  }

  public GroupMemorySetting updateGroupMemory(String accountId, String chatGuid, boolean enabled) {
    GroupMemoryUpdateResult result = tryUpdateGroupMemory(accountId, chatGuid, enabled);
    if (!result.success()) {
      throw new IllegalStateException(result.message());
    }
    return result.setting();
  }

  public GroupMemoryUpdateResult tryUpdateGroupMemory(
      String accountId, String chatGuid, boolean enabled) {
    if (StringUtils.isBlank(accountId) || StringUtils.isBlank(chatGuid)) {
      return failure(
          "A linked account and current conversation are required.", unavailableSetting());
    }
    if (!globallyEnabled) {
      return failure("Group memory is not enabled for this deployment.", globallyDisabledSetting());
    }
    Optional<ConversationRecord> conversation = findOrRegisterConversation(chatGuid);
    if (conversation.isEmpty() || !conversation.get().group()) {
      return failure(
          "Group memory is only available in group conversations.", unavailableSetting());
    }
    ConversationRecord current = conversation.get();
    boolean currentlyEnabled = current.memoryEnabledAt() != null;
    if (currentlyEnabled == enabled) {
      return success(
          enabled ? "Group memory is already enabled." : "Group memory is already disabled.",
          toSetting(current));
    }

    Instant now = clock.instant();
    if (enabled) {
      store.enableMemory(current.conversationId(), accountId, now);
      if (!sendNotice(chatGuid, ENABLE_NOTICE)) {
        store.disableMemory(current.conversationId(), now);
        return failure(
            "I could not confirm the group notice, so group memory remains disabled.",
            getGroupMemory(chatGuid));
      }
      return success("Group memory enabled.", getGroupMemory(chatGuid));
    }

    store.disableMemory(current.conversationId(), now);
    boolean noticeSent = sendNotice(chatGuid, DISABLE_NOTICE);
    return new GroupMemoryUpdateResult(
        noticeSent,
        noticeSent
            ? "Group memory disabled."
            : "Group memory was disabled, but I could not confirm the group notice.",
        getGroupMemory(chatGuid));
  }

  private Optional<ConversationRecord> findOrRegisterConversation(String chatGuid) {
    if (StringUtils.isBlank(chatGuid)) {
      return Optional.empty();
    }
    Optional<ConversationRecord> existing =
        store.findConversation(IncomingMessage.TRANSPORT_BLUEBUBBLES, chatGuid);
    if (existing.isPresent()) {
      return existing;
    }
    try {
      JsonNode info = bbHttpClientWrapper.getConversationInfoJson(chatGuid);
      if (info == null || !info.has("isGroup")) {
        return Optional.empty();
      }
      Instant now = clock.instant();
      String conversationId =
          store.upsertConversation(
              IncomingMessage.TRANSPORT_BLUEBUBBLES,
              chatGuid,
              info.path("isGroup").asBoolean(false),
              StringUtils.defaultIfBlank(info.path("displayName").asText(null), null),
              now);
      return store.findConversation(conversationId);
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  private boolean sendNotice(String chatGuid, String message) {
    ApiV1MessageTextPostRequest request = new ApiV1MessageTextPostRequest();
    request.setChatGuid(chatGuid);
    request.setMessage(message);
    request.setTempGuid(UUID.randomUUID().toString());
    try {
      return bbHttpClientWrapper.sendTextDirect(request);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private GroupMemorySetting toSetting(ConversationRecord conversation) {
    boolean enabled = conversation.memoryEnabledAt() != null;
    return new GroupMemorySetting(
        true,
        enabled,
        "Memory",
        enabled
            ? "New collective decisions can be used in authorized personal chats."
            : "Starts prospectively after a visible notice in this group.",
        conversation.memoryEnabledAt());
  }

  private GroupMemorySetting unavailableSetting() {
    return new GroupMemorySetting(
        false, false, "Memory", "Group memory is unavailable for direct conversations.", null);
  }

  private GroupMemorySetting globallyDisabledSetting() {
    return new GroupMemorySetting(
        false, false, "Memory", "Group memory is not enabled for this deployment.", null);
  }

  private GroupMemoryUpdateResult success(String message, GroupMemorySetting setting) {
    return new GroupMemoryUpdateResult(true, message, setting);
  }

  private GroupMemoryUpdateResult failure(String message, GroupMemorySetting setting) {
    return new GroupMemoryUpdateResult(false, message, setting);
  }

  public record GroupMemorySetting(
      boolean available,
      boolean enabled,
      String label,
      String description,
      Instant collectionStartedAt) {}

  public record GroupMemoryUpdateResult(
      boolean success, String message, GroupMemorySetting setting) {}
}

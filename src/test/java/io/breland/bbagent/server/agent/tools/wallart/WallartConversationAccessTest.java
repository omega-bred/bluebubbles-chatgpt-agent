package io.breland.bbagent.server.agent.tools.wallart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WallartConversationAccessTest {
  private static final String CHAT_GUID = "iMessage;+;group-chat";

  @Test
  void allowsDirectChatWithConfiguredParticipantUsingNormalizedPhoneNumber() {
    BBHttpClientWrapper wrapper = mock(BBHttpClientWrapper.class);
    WallartConversationAccess access = access(wrapper);

    assertTrue(access.isAllowed(message("tel:(803) 386-1737", false)));
    verify(wrapper, never()).getConversationInfoJson(CHAT_GUID);
  }

  @Test
  void allowsGroupWhenConfiguredParticipantIsInConversation() {
    BBHttpClientWrapper wrapper = mock(BBHttpClientWrapper.class);
    when(wrapper.getConversationInfoJson(CHAT_GUID))
        .thenReturn(conversationWithParticipant("+1 803-386-1737"));
    WallartConversationAccess access = access(wrapper);

    assertTrue(access.isAllowed(message("someone@example.com", true)));
  }

  @Test
  void deniesOtherDirectChatsAndGroupsWithoutConfiguredParticipant() {
    BBHttpClientWrapper wrapper = mock(BBHttpClientWrapper.class);
    when(wrapper.getConversationInfoJson(CHAT_GUID))
        .thenReturn(conversationWithParticipant("+14155550123"));
    WallartConversationAccess access = access(wrapper);

    assertFalse(access.isAllowed(message("+14155550123", false)));
    assertFalse(access.isAllowed(message("someone@example.com", true)));
  }

  @Test
  void failsClosedWhenGroupParticipantsCannotBeLoaded() {
    BBHttpClientWrapper wrapper = mock(BBHttpClientWrapper.class);
    when(wrapper.getConversationInfoJson(CHAT_GUID))
        .thenThrow(new IllegalStateException("unavailable"));

    assertFalse(access(wrapper).isAllowed(message("someone@example.com", true)));
  }

  private static WallartConversationAccess access(BBHttpClientWrapper wrapper) {
    WallartMcpProperties properties = new WallartMcpProperties();
    return new WallartConversationAccess(wrapper, properties);
  }

  private static ObjectNode conversationWithParticipant(String address) {
    ObjectNode conversation = new ObjectMapper().createObjectNode();
    conversation.putArray("participants").addObject().put("address", address);
    return conversation;
  }

  private static IncomingMessage message(String sender, boolean group) {
    return new IncomingMessage(
        CHAT_GUID,
        "message-guid",
        null,
        "hello",
        false,
        "iMessage",
        sender,
        group,
        Instant.EPOCH,
        List.of(),
        false);
  }
}

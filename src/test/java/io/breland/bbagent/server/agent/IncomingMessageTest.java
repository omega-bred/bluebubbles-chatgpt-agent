package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataAttachmentsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataChatsInner;
import io.breland.bbagent.generated.model.LxmfMessageReceivedRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncomingMessageTest {

  @Test
  void likelyGroupChatUsesGroupFlag() {
    IncomingMessage message =
        new IncomingMessage(
            "iMessage;+;user-1",
            "msg-1",
            null,
            "hello",
            false,
            "iMessage",
            "Alice",
            true,
            Instant.now(),
            List.of(),
            false);

    assertTrue(message.isGroup());
  }

  @Test
  void likelyGroupChatDoesNotInferFromChatGuidPrefixWhenGroupFlagIsFalse() {
    IncomingMessage message =
        new IncomingMessage(
            "iMessage;+;chat123",
            "msg-1",
            null,
            "hello",
            false,
            "iMessage",
            "Alice",
            false,
            Instant.now(),
            List.of(),
            false);

    assertFalse(message.isGroup());
  }

  @Test
  void likelyGroupChatReturnsFalseForDirectChatsWithoutGroupFlag() {
    IncomingMessage message =
        new IncomingMessage(
            "iMessage;+;user-1",
            "msg-1",
            null,
            "hello",
            false,
            "iMessage",
            "Alice",
            false,
            Instant.now(),
            List.of(),
            false);

    assertFalse(message.isGroup());
  }

  @Test
  void fromBlueBubblesHistoryNormalizesGroupPrefixAndEpochSeconds() {
    ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner chat =
        new ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner().guid("any;+;chat-1");
    ApiV1ChatChatGuidMessageGet200ResponseDataInner message =
        new ApiV1ChatChatGuidMessageGet200ResponseDataInner()
            .guid("msg-1")
            .text("hello")
            .isFromMe(false)
            .chats(List.of(chat))
            .dateCreated(1_700_000_000L);

    IncomingMessage incoming = IncomingMessage.fromBlueBubblesHistory(message);

    assertEquals("any;+;chat-1", incoming.chatGuid());
    assertTrue(incoming.isGroup());
    assertEquals(Instant.ofEpochSecond(1_700_000_000L), incoming.timestamp());
  }

  @Test
  void fromBlueBubblesWebhookNormalizesAttachmentsAndEpochMillis() {
    BlueBubblesMessageReceivedRequestDataChatsInner chat =
        new BlueBubblesMessageReceivedRequestDataChatsInner().guid("iMessage;+;chat-2");
    BlueBubblesMessageReceivedRequestDataAttachmentsInner attachment =
        new BlueBubblesMessageReceivedRequestDataAttachmentsInner()
            .guid("att-1")
            .mimeType("image/png")
            .transferName("photo.png");
    BlueBubblesMessageReceivedRequestData data =
        new BlueBubblesMessageReceivedRequestData()
            .guid("msg-2")
            .text("photo")
            .isFromMe(false)
            .dateCreated(1_700_000_000_000L)
            .chats(List.of(chat))
            .attachments(List.of(attachment));

    IncomingMessage incoming = IncomingMessage.fromBlueBubblesWebhook(data);

    assertEquals("iMessage;+;chat-2", incoming.chatGuid());
    assertTrue(incoming.isGroup());
    assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), incoming.timestamp());
    assertEquals(1, incoming.attachments().size());
    assertEquals("att-1", incoming.attachments().getFirst().guid());
    assertEquals("image/png", incoming.attachments().getFirst().mimeType());
    assertEquals("photo.png", incoming.attachments().getFirst().filename());
  }

  @Test
  void fromLxmfWebhookNormalizesSourceHashAndTimestamp() {
    OffsetDateTime timestamp =
        OffsetDateTime.ofInstant(Instant.ofEpochSecond(1234), ZoneOffset.UTC);
    LxmfMessageReceivedRequest request =
        new LxmfMessageReceivedRequest()
            .messageId("lxmf-msg-1")
            .sourceHash(" ABCDEF ")
            .content("hello over lxmf")
            .timestamp(timestamp);

    IncomingMessage incoming = IncomingMessage.fromLxmfWebhook(request);

    assertEquals(IncomingMessage.TRANSPORT_LXMF, incoming.transport());
    assertEquals("lxmf:abcdef", incoming.chatGuid());
    assertEquals("abcdef", incoming.sender());
    assertEquals("hello over lxmf", incoming.text());
    assertEquals(timestamp.toInstant(), incoming.timestamp());
  }
}

package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest()
@Disabled
public class BBClientTest {

  @Autowired public BBHttpClientWrapper bbHttpClientWrapper;

  @Test
  public void testGetHistory() {
    List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> worked =
        bbHttpClientWrapper.getMessagesInChat("iMessage;+;chat293505621450166166");
    assertNotNull(worked);
  }

  @Test
  public void updateGroupIcon() {
    boolean worked =
        bbHttpClientWrapper.setConversationIcon(
            "iMessage;+;chat293505621450166166", Path.of("/Users/breland/Downloads/back.png"));
    assertTrue(worked);
  }

  @Test
  public void testRename() {
    String chatGuid = "iMessage;+;chat293505621450166166";
    String newName = UUID.randomUUID().toString();
    boolean jd = bbHttpClientWrapper.renameConversation(chatGuid, newName);
    assertTrue(jd);
    Chat chat = bbHttpClientWrapper.getConversationInfo(chatGuid);
    assertNotNull(chat);
    assertEquals(chat.getDisplayName(), newName);
  }

  @Test
  public void testGetMessages() {
    List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> messagesInChat =
        bbHttpClientWrapper.getMessagesInChat("iMessage;+;chat293505621450166166");
  }

  @Test
  public void testGetMessage() {
    Message msg = bbHttpClientWrapper.getMessage("8D7D7D09-DD10-425F-9B72-8EF322ECA49D");
    assertNotNull(msg);
  }

  @Test
  public void testConvoSearch() {
    String chatGuid = "iMessage;+;chat293505621450166166";
    String query = "JD";
    List<Message> messages =
        bbHttpClientWrapper.searchConversationHistory(chatGuid, query, null, null);
    assertNotNull(messages);
    assert messages.size() > 0;
    for (Message message : messages) {
      assert message.getText().toLowerCase().contains(query.toLowerCase());
    }
  }
}

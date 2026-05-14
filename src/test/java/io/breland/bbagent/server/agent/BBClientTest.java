package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.generated.bluebubblesclient.model.FindMyFriendLocation;
import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest()
@DisabledOnOs(OS.LINUX)
public class BBClientTest {

  @Autowired public BBHttpClientWrapper bbHttpClientWrapper;

  @Test
  public void testLocation() {
    FindMyFriendLocation findMyLocation =
        bbHttpClientWrapper.getFindMyLocation("mindstorms6+apple@gmail.com");
    assertNotNull(findMyLocation);
  }

  @Test
  public void testGetHistory() {
    List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> worked =
        bbHttpClientWrapper.getMessagesInChat("any;+;chat293505621450166166");
    assertNotNull(worked);
  }

  @Test
  public void updateGroupIcon() {
    boolean worked =
        bbHttpClientWrapper.setConversationIcon(
            "any;+;chat293505621450166166", Path.of("/Users/breland/Downloads/back.png"));
    assertTrue(worked);
  }

  @Test
  public void testRename() {
    String chatGuid = "any;+;chat293505621450166166";
    String newName = UUID.randomUUID().toString();
    boolean jd = bbHttpClientWrapper.renameConversation(chatGuid, newName);
    assertTrue(jd);
    Chat chat = bbHttpClientWrapper.getConversationInfo(chatGuid);
    assertNotNull(chat);
    assertEquals(chat.getDisplayName(), newName);
  }

  @Test
  public void testGetAccount() {
    var account = bbHttpClientWrapper.getAccount();
    System.out.println(account.toString());
  }

  @Test
  public void testGetMessages() {
    List<ApiV1ChatChatGuidMessageGet200ResponseDataInner> messagesInChat =
        bbHttpClientWrapper.getMessagesInChat("any;+;chat293505621450166166");
  }

  @Test
  public void testGetMessage() {
    Message msg = bbHttpClientWrapper.getMessage("6415C258-CAF7-45B1-99F6-1ED174764021");
    assertNotNull(msg);
  }

  @Test
  public void testConvoSearch() {
    String chatGuid = "any;+;chat293505621450166166";
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

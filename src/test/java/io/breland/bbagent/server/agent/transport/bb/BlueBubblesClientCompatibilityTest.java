package io.breland.bbagent.server.agent.transport.bb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.breland.bbagent.generated.bluebubblesclient.ApiClient;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiResponseGetChat;
import org.junit.jupiter.api.Test;

class BlueBubblesClientCompatibilityTest {

  @Test
  void decodesHexEncodedChatGroupIdFromServerResponse() throws Exception {
    String groupId = "44423736393942422D384343342D344534422D383438372D463532344646323635334334";
    String responseBody =
        """
        {
          "status": 200,
          "message": "success",
          "data": {
            "groupId": "%s"
          }
        }
        """
            .formatted(groupId);

    ApiResponseGetChat response =
        ApiClient.createDefaultMapper(null).readValue(responseBody, ApiResponseGetChat.class);

    assertEquals(groupId, String.valueOf(response.getData().getGroupId()));
  }
}

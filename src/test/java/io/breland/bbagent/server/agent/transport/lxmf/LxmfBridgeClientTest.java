package io.breland.bbagent.server.agent.transport.lxmf;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LxmfBridgeClientTest {

  @Test
  void sendsContentToBridge() {
    RestClient.Builder restClientBuilder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
    server
        .expect(requestTo("https://lxmf.example/api/v1/messages/send"))
        .andExpect(
            content()
                .json(
                    """
                    {"destination_hash":"aabb","content":"SECRET_LXMF_OUTBOUND_MESSAGE"}
                    """))
        .andRespond(withSuccess());
    LxmfBridgeClient client =
        new LxmfBridgeClient(restClientBuilder, "https://lxmf.example", "secret");

    assertTrue(client.sendText("aabb", "SECRET_LXMF_OUTBOUND_MESSAGE"));
    server.verify();
  }
}

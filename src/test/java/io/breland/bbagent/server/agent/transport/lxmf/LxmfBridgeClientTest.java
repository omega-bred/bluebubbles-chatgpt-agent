package io.breland.bbagent.server.agent.transport.lxmf;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class LxmfBridgeClientTest {

  @Test
  void sendTextPostsMessageToBridge() throws IOException {
    AtomicReference<String> requestPath = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/messages/send",
        exchange -> {
          requestPath.set(exchange.getRequestURI().getPath());
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try {
      LxmfBridgeClient client =
          new LxmfBridgeClient(
              RestClient.builder(),
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "bridge-secret");

      assertThat(client.sendText("destination-1", "hello")).isTrue();

      assertThat(requestPath.get()).isEqualTo("/api/v1/messages/send");
      assertThat(authorization.get()).isEqualTo("Bearer bridge-secret");
      assertThat(requestBody.get())
          .contains("\"destination_hash\":\"destination-1\"")
          .contains("\"content\":\"hello\"");
    } finally {
      server.stop(0);
    }
  }
}

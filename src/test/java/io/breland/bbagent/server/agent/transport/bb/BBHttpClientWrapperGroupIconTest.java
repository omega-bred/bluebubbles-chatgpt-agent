package io.breland.bbagent.server.agent.transport.bb;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class BBHttpClientWrapperGroupIconTest {
  private HttpServer server;
  private BBHttpClientWrapper wrapper;
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<byte[]> response = new AtomicReference<>(new byte[] {1, 2, 3});
  private final AtomicReference<URI> uri = new AtomicReference<>();
  private final AtomicReference<String> method = new AtomicReference<>();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/api/v1/chat/",
        exchange -> {
          uri.set(exchange.getRequestURI());
          method.set(exchange.getRequestMethod());
          exchange.getResponseHeaders().add("Content-Type", "image/png");
          exchange.sendResponseHeaders(status.get(), 0);
          try (var body = exchange.getResponseBody()) {
            body.write(response.get());
          }
        });
    server.start();
    String url =
        new URI(
                "http",
                null,
                server.getAddress().getAddress().getHostAddress(),
                server.getAddress().getPort(),
                null,
                null,
                null)
            .toString();
    wrapper = new BBHttpClientWrapper(url, "test-password", 5, new ObjectMapper(), null);
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void fetchesAuthenticatedIconBytesBeyondTheNormalJsonLimit() {
    byte[] bytes = new byte[3 * 1024 * 1024];
    bytes[0] = 42;
    response.set(bytes);
    assertArrayEquals(bytes, wrapper.getConversationIcon("any;+;group").orElseThrow());
    assertEquals("GET", method.get());
    assertEquals("/api/v1/chat/any;+;group/icon", uri.get().getPath());
    assertEquals("password=test-password", uri.get().getQuery());
  }

  @Test
  void distinguishesAbsentPhotoFromUpstreamFailure() {
    status.set(404);
    assertTrue(wrapper.getConversationIcon("any;+;group").isEmpty());
    status.set(500);
    assertThrows(
        WebClientResponseException.class, () -> wrapper.getConversationIcon("any;+;group"));
  }

  @Test
  void boundsChunkedResponsesAndRejectsMissingChat() {
    response.set(new byte[10 * 1024 * 1024 + 1]);
    assertThrows(DataBufferLimitException.class, () -> wrapper.getConversationIcon("any;+;group"));
    assertThrows(IllegalArgumentException.class, () -> wrapper.getConversationIcon(" "));
  }
}

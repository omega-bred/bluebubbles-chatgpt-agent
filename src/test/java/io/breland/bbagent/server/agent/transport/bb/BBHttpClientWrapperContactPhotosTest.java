package io.breland.bbagent.server.agent.transport.bb;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BBHttpClientWrapperContactPhotosTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private HttpServer server;
  private BBHttpClientWrapper wrapper;
  private final AtomicReference<String> response = new AtomicReference<>();
  private final AtomicReference<JsonNode> request = new AtomicReference<>();
  private final AtomicReference<String> query = new AtomicReference<>();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/api/v1/contact/query",
        exchange -> {
          request.set(mapper.readTree(exchange.getRequestBody()));
          byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var body = exchange.getResponseBody()) {
            body.write(bytes);
          }
        });
    server.createContext(
        "/api/v1/icloud/contact",
        exchange -> {
          query.set(exchange.getRequestURI().getQuery());
          byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var body = exchange.getResponseBody()) {
            body.write(bytes);
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
    wrapper = new BBHttpClientWrapper(url, "test-password", 5, mapper, null);
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void sendsTypedScopedAvatarQueryAndReadsBeyondNormalCodecLimit() throws Exception {
    String avatar = "a".repeat(3 * 1024 * 1024);
    response.set(
        mapper.writeValueAsString(
            java.util.Map.of(
                "status",
                200,
                "message",
                "Success",
                "data",
                java.util.List.of(java.util.Map.of("displayName", "Alice", "avatar", avatar)))));
    var result = wrapper.getContactPhotosForAddress("alice@example.com", Duration.ofSeconds(5));
    assertEquals(avatar, result.getFirst().getAvatar());
    assertEquals(
        mapper.readTree("{\"addresses\":[\"alice@example.com\"],\"extraProperties\":[\"avatar\"]}"),
        request.get());
  }

  @Test
  void sharedProfileAlwaysHasAnExplicitAddressAndValidatesEnvelope() {
    response.set(
        "{\"status\":200,\"message\":\"Success\",\"data\":{\"name\":\"Alice\",\"avatar\":\"abc\"}}");
    assertEquals(
        "Alice",
        wrapper
            .getSharedContactPhoto("alice@example.com", Duration.ofSeconds(5))
            .path("name")
            .asText());
    assertTrue(query.get().contains("address=alice@example.com"));
    response.set("{\"status\":500,\"message\":\"unavailable\"}");
    assertThrows(
        IllegalStateException.class,
        () -> wrapper.getSharedContactPhoto("alice@example.com", Duration.ofSeconds(5)));
    assertThrows(
        IllegalStateException.class,
        () -> wrapper.getContactPhotosForAddress("alice@example.com", Duration.ofSeconds(5)));
    assertThrows(
        IllegalArgumentException.class,
        () -> wrapper.getSharedContactPhoto(" ", Duration.ofSeconds(5)));
    assertThrows(
        IllegalArgumentException.class,
        () -> wrapper.getContactPhotosForAddress("", Duration.ofSeconds(5)));
  }

  @Test
  void enforcesPhotoResponseSizeAndTimeBounds() {
    response.set(
        "{\"status\":200,\"data\":[],\"message\":\"" + "a".repeat(16 * 1024 * 1024) + "\"}");
    assertThrows(
        org.springframework.core.io.buffer.DataBufferLimitException.class,
        () -> wrapper.getContactPhotosForAddress("alice@example.com", Duration.ofSeconds(5)));
    assertThrows(
        IllegalStateException.class,
        () -> wrapper.getContactPhotosForAddress("alice@example.com", Duration.ZERO));
  }
}

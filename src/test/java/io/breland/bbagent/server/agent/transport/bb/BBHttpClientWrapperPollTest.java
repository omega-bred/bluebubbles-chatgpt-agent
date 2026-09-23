package io.breland.bbagent.server.agent.transport.bb;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BBHttpClientWrapperPollTest {
  @ParameterizedTest
  @ValueSource(strings = {"[]", "[{\"text\":\"Dinner?\"}]", "\"Dinner?\"", "null"})
  void decodesSendAndReadResponsesThroughGeneratedClient(String attributedBody) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    AtomicInteger sends = new AtomicInteger();
    String poll =
        """
        {"messageGuid":"12345678-1234-1234-1234-123456789abc","title":"Dinner?",
         "options":[
           {"optionIdentifier":"a","text":"Sushi","attributedText":"Sushi"},
           {"optionIdentifier":"b","text":"Pizza","attributedText":{"text":"Pizza"}},
           {"optionIdentifier":"c","text":"Thai","attributedText":[{"text":"Thai"}]},
           {"optionIdentifier":"d","text":"Other","attributedText":null}],
         "responses":[{"handle":"test@example.com","optionIdentifiers":["a"]}]}
        """;
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/message/",
        exchange -> {
          String body;
          if (exchange.getRequestMethod().equals("POST")) {
            sends.incrementAndGet();
            var request = mapper.readTree(exchange.getRequestBody());
            assertEquals("iMessage;-;+15555550123", request.path("chatGuid").asText());
            body =
                "{\"status\":200,\"message\":\"Poll sent!\",\"data\":{\"message\":{\"guid\":\"12345678-1234-1234-1234-123456789abc\",\"attributedBody\":"
                    + attributedBody
                    + "},\"poll\":"
                    + poll
                    + "}}";
          } else {
            assertEquals(
                "/api/v1/message/12345678-1234-1234-1234-123456789abc/poll",
                exchange.getRequestURI().getPath());
            body = "{\"status\":200,\"message\":\"Poll read!\",\"data\":" + poll + "}";
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    try {
      var wrapper =
          new BBHttpClientWrapper(
              "http://127.0.0.1:" + server.getAddress().getPort(), "pw", 5, mapper, null);
      var result =
          wrapper.sendPollJson(
              "any;-;+15555550123",
              "Dinner?",
              List.of(
                  new BBHttpClientWrapper.PollSendOption("Sushi", null),
                  new BBHttpClientWrapper.PollSendOption("Pizza", null)));
      assertEquals(
          "12345678-1234-1234-1234-123456789abc", result.path("message").path("guid").asText());
      if (!attributedBody.equals("null")) {
        assertEquals(
            mapper.readTree(attributedBody), result.path("message").path("attributedBody"));
      } else {
        assertTrue(
            result.path("message").path("attributedBody").isNull()
                || result.path("message").path("attributedBody").isMissingNode());
      }
      var read = wrapper.readPollJson("12345678-1234-1234-1234-123456789abc");
      assertEquals("Sushi", read.path("options").get(0).path("attributedText").asText());
      assertEquals(
          "Pizza", read.path("options").get(1).path("attributedText").path("text").asText());
      assertTrue(
          BlueBubblesPollSupport.formatPollReadResult(
                  read, "12345678-1234-1234-1234-123456789abc", List.of())
              .contains("Sushi - 1 vote"));
      assertEquals(1, sends.get());
    } finally {
      server.stop(0);
    }
  }
}

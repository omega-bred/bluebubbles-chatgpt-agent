package io.breland.bbagent.server.linear;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.breland.bbagent.server.linear.LinearIssueService.FeedbackIssueInput;
import io.breland.bbagent.server.linear.LinearIssueService.LinearIssueException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class LinearIssueServiceTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void createsFeedbackIssueWithConfiguredTeamAndLabel() throws IOException {
    AtomicReference<String> authorizationHeader = new AtomicReference<>();
    AtomicReference<JsonNode> createdIssueInput = new AtomicReference<>();
    AtomicInteger callCount = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/graphql",
        exchange -> {
          authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
          JsonNode request =
              mapper.readTree(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String query = request.path("query").asText();
          String response;
          if (query.contains("LinearRouting")) {
            callCount.incrementAndGet();
            response =
                """
                {
                  "data": {
                    "teams": {
                      "nodes": [
                        { "id": "team-bluechat", "key": "BLU", "name": "Bluechat" }
                      ]
                    },
                    "issueLabels": {
                      "nodes": [
                        { "id": "label-improvement", "name": "Improvement", "team": null },
                        { "id": "label-contact", "name": "Contact/Help", "team": null }
                      ]
                    }
                  }
                }
                """;
          } else if (query.contains("IssueCreate")) {
            callCount.incrementAndGet();
            createdIssueInput.set(request.path("variables").path("input"));
            response =
                """
                {
                  "data": {
                    "issueCreate": {
                      "success": true,
                      "issue": {
                        "id": "issue-id",
                        "identifier": "BLU-456",
                        "title": "[Feedback/tool] tool idea",
                        "url": "https://linear.app/bluechat/issue/BLU-456/tool-idea",
                        "createdAt": "2026-05-01T00:00:00.000Z"
                      }
                    }
                  }
                }
                """;
          } else {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
          }
          byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBytes.length);
          exchange.getResponseBody().write(responseBytes);
          exchange.close();
        });
    server.start();
    try {
      LinearIssueService service =
          new LinearIssueService(properties(server), WebClient.builder(), mapper);

      var issue =
          service.createFeedbackIssue(
              new FeedbackIssueInput(
                  "account-1",
                  Instant.parse("2026-05-01T00:00:00Z"),
                  "tool idea",
                  "tool",
                  "iMessage",
                  "Alice",
                  "chat-guid",
                  "message-guid"));

      assertThat(issue.reference()).isEqualTo("BLU-456");
      assertThat(callCount.get()).isEqualTo(2);
      assertThat(authorizationHeader.get()).isEqualTo("lin_api_test");
      assertThat(createdIssueInput.get().path("teamId").asText()).isEqualTo("team-bluechat");
      assertThat(createdIssueInput.get().path("labelIds").get(0).asText())
          .isEqualTo("label-improvement");
      assertThat(createdIssueInput.get().path("description").asText())
          .contains("account-1", "tool idea", "Alice");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void failsWhenConfiguredLabelIsMissing() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/graphql",
        exchange -> {
          byte[] response =
              """
              {
                "data": {
                  "teams": {
                    "nodes": [
                      { "id": "team-bluechat", "key": "BLU", "name": "Bluechat" }
                    ]
                  },
                  "issueLabels": {
                    "nodes": []
                  }
                }
              }
              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      LinearIssueService service =
          new LinearIssueService(properties(server), WebClient.builder(), mapper);

      assertThatThrownBy(
              () ->
                  service.createFeedbackIssue(
                      new FeedbackIssueInput(
                          "account-1",
                          Instant.parse("2026-05-01T00:00:00Z"),
                          "tool idea",
                          "tool",
                          "iMessage",
                          "Alice",
                          "chat-guid",
                          "message-guid")))
          .isInstanceOf(LinearIssueException.class)
          .hasMessageContaining("Linear label is missing: Improvement");
    } finally {
      server.stop(0);
    }
  }

  private LinearProperties properties(HttpServer server) {
    LinearProperties properties = new LinearProperties();
    properties.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/graphql");
    properties.setApiKey("lin_api_test");
    properties.setTeamName("bluechat");
    properties.setCreateMissingLabels(false);
    return properties;
  }
}

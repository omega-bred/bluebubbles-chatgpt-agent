package io.breland.bbagent.server.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.MessageIngressPipeline;
import io.breland.bbagent.server.config.SecurityConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BluebubblesWebhookController.class)
@Import(SecurityConfig.class)
class BluebubblesWebhookControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private MessageIngressPipeline messageIngressPipeline;

  @Test
  void messageEventEnqueuesNormalizedMessage() throws Exception {
    when(messageIngressPipeline.validateMessageEventPayload(any())).thenReturn(null);
    when(messageIngressPipeline.normalizeWebhookMessage(any())).thenReturn(incomingMessage());
    when(messageIngressPipeline.enqueue(any(), any())).thenReturn(true);

    mockMvc
        .perform(
            post("/api/v1/bluebubbles/messageReceived.message")
                .contentType(APPLICATION_JSON)
                .content(validRequestJson()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("queued"));
  }

  @Test
  void invalidMessagePayloadReturnsBadRequest() throws Exception {
    when(messageIngressPipeline.validateMessageEventPayload(any())).thenReturn("missing_chat_guid");

    mockMvc
        .perform(
            post("/api/v1/bluebubbles/messageReceived.message")
                .contentType(APPLICATION_JSON)
                .content(validRequestJson()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(jsonPath("$.error").value("missing_chat_guid"));
    verify(messageIngressPipeline).captureMalformedPayload(any(), any());
  }

  @Test
  void queueFullResponseIsReported() throws Exception {
    when(messageIngressPipeline.validateMessageEventPayload(any())).thenReturn(null);
    when(messageIngressPipeline.normalizeWebhookMessage(any())).thenReturn(incomingMessage());
    when(messageIngressPipeline.enqueue(any(), any())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/v1/bluebubbles/messageReceived.message")
                .contentType(APPLICATION_JSON)
                .content(validRequestJson()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("dropped_queue_full"));
  }

  @Test
  void typingIndicatorReturnsOkWithoutPipelineCalls() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/bluebubbles/messageReceived.message")
                .contentType(APPLICATION_JSON)
                .content(typingIndicatorRequestJson()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"));

    verify(messageIngressPipeline, never()).validateMessageEventPayload(any());
    verify(messageIngressPipeline, never()).normalizeWebhookMessage(any());
    verify(messageIngressPipeline, never()).enqueue(any(), any());
  }

  @Test
  void nullNormalizedPayloadReturnsIgnored() throws Exception {
    when(messageIngressPipeline.validateMessageEventPayload(any())).thenReturn(null);
    when(messageIngressPipeline.normalizeWebhookMessage(any())).thenReturn(null);

    mockMvc
        .perform(
            post("/api/v1/bluebubbles/messageReceived.message")
                .contentType(APPLICATION_JSON)
                .content(validRequestJson()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ignored"));

    verify(messageIngressPipeline, never()).enqueue(any(), any());
  }

  private IncomingMessage incomingMessage() {
    return new IncomingMessage(
        "iMessage;+;chat-1",
        "msg-1",
        null,
        "hello",
        false,
        "iMessage",
        "alice@example.com",
        false,
        Instant.now(),
        List.of(),
        false);
  }

  private String validRequestJson() {
    return """
        {
          "type": "new-message",
          "data": {
            "originalROWID": 1,
            "guid": "msg-1",
            "text": "hello",
            "handle": {
              "address": "alice@example.com",
              "service": "iMessage"
            },
            "chats": [
              {
                "originalROWID": 1,
                "guid": "iMessage;+;chat-1",
                "style": 0,
                "chatIdentifier": "chat-1",
                "isArchived": false,
                "displayName": "Chat 1"
              }
            ]
          }
        }
        """;
  }

  private String typingIndicatorRequestJson() {
    return """
        {
          "type": "typing-indicator",
          "data": {
            "originalROWID": 1,
            "guid": "typing-1"
          }
        }
        """;
  }
}

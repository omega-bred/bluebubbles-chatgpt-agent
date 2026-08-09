package io.breland.bbagent.server.security;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawMessageLogSafetyTest {

  @Test
  void transportLogsDoNotSerializeRawMessages() throws IOException {
    for (ForbiddenLog forbiddenLog : FORBIDDEN_LOGS) {
      String source = Files.readString(Path.of(forbiddenLog.sourcePath()));
      assertFalse(
          source.contains(forbiddenLog.snippet()),
          () -> "Raw message log remains in " + forbiddenLog.sourcePath());
    }
  }

  private static final List<ForbiddenLog> FORBIDDEN_LOGS =
      List.of(
          forbidden(
              "controllers/BluebubblesWebhookController.java",
              "log.info(\"Incoming Message {}\", requestBody);"),
          forbidden(
              "controllers/LxmfWebhookController.java",
              "log.info(\"Incoming LXMF message {}\", message);"),
          forbidden(
              "agent/cadence/CadenceIncomingMessageHandler.java",
              "log.debug(\"Dropping message {}\", rawMessage);"),
          forbidden(
              "agent/cadence/CadenceIncomingMessageHandler.java",
              "log.info(\"Processing Message {}\", rawMessage);"),
          forbidden(
              "agent/cadence/CadenceIncomingMessageHandler.java",
              "log.warn(\"Failed to record message metric for {}\", message, e);"),
          forbidden(
              "agent/transport/bb/BBHttpClientWrapper.java",
              "Sending multipart message with chatGuid {} - message {}"),
          forbidden(
              "agent/transport/bb/BBHttpClientWrapper.java",
              "Attempting to send direct text message chatGuid={} confirmationChatGuid={} tempGuid={} attempt={}/{} timeout={} request={}"),
          forbidden(
              "agent/transport/lxmf/LxmfBridgeClient.java",
              "log.info(\"Sending message to Lxmf bridge {}: {}\", destinationHash, content);"),
          forbidden(
              "agent/transport/lxmf/LxmfBridgeClient.java",
              "log.warn(\"Failed to send LXMF message to {}\", destinationHash, e);"));

  private static ForbiddenLog forbidden(String sourcePath, String snippet) {
    return new ForbiddenLog("src/main/java/io/breland/bbagent/server/" + sourcePath, snippet);
  }

  private record ForbiddenLog(String sourcePath, String snippet) {}
}

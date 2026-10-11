package io.breland.bbagent.server.agent.transport.lxmf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LxmfBridgeClientTest {
  private static final String DESTINATION = "aabbccddeeff00112233445566778899";
  private static final String CONTENT = "PRIVATE_LXMF_MESSAGE: café 🌍\r\nFORGED_LOG_ENTRY";
  private static final String SECRET = "PRIVATE_BRIDGE_SECRET";
  private static final String URL = "https://bridge.example/api/v1/messages/send";

  private final List<LogEvent> events = new ArrayList<>();
  private final Logger logger = (Logger) LogManager.getLogger(LxmfBridgeClient.class);
  private final AbstractAppender appender =
      new AbstractAppender("lxmf-test", null, null, true, Property.EMPTY_ARRAY) {
        @Override
        public void append(LogEvent event) {
          events.add(event.toImmutable());
        }
      };
  private Level originalLevel;
  private MockRestServiceServer server;
  private LxmfBridgeClient client;

  @BeforeEach
  void setUp() {
    originalLevel = logger.getLevel();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.ALL);
    var builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client = new LxmfBridgeClient(builder, "https://bridge.example", SECRET);
  }

  @AfterEach
  void tearDown() {
    logger.removeAppender(appender);
    logger.setLevel(originalLevel);
    appender.stop();
  }

  @ParameterizedTest
  @MethodSource("validMessages")
  void preservesRequestAndSuccessfulResult(String destination, String content) throws Exception {
    expectSend(destination, content).andRespond(withNoContent());

    assertThat(client.sendText(destination, content)).isTrue();
    server.verify();
  }

  static Stream<Arguments> validMessages() {
    return Stream.of(
        Arguments.of(DESTINATION, CONTENT),
        Arguments.of(" " + DESTINATION.toUpperCase() + " ", "  keep whitespace  "),
        Arguments.of(DESTINATION, "{\"message\":\"quoted\\content\"}"));
  }

  @ParameterizedTest
  @MethodSource("invalidMessages")
  void rejectsMissingValuesWithoutSending(String destination, String content) {
    assertThat(client.sendText(destination, content)).isFalse();
    server.verify();
  }

  static Stream<Arguments> invalidMessages() {
    return Stream.of(
        Arguments.of(null, CONTENT),
        Arguments.of("", CONTENT),
        Arguments.of(" \t\n", CONTENT),
        Arguments.of(DESTINATION, null),
        Arguments.of(DESTINATION, ""),
        Arguments.of(DESTINATION, " \t\n"));
  }

  @Test
  void neverLogsTheOutgoingPayloadAtAnyLevel() throws Exception {
    expectSend(DESTINATION, CONTENT).andRespond(withNoContent());

    assertThat(client.sendText(DESTINATION, CONTENT)).isTrue();
    assertPrivateLogsAbsent();
    server.verify();
  }

  @ParameterizedTest
  @MethodSource("invalidMessages")
  void rejectedInputsDoNotReachLogs(String destination, String content) {
    assertThat(client.sendText(destination, content)).isFalse();
    assertPrivateLogsAbsent();
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 429, 500, 503})
  void providerErrorsKeepSafeDiagnosticsWithoutEchoedRequestData(int status) throws Exception {
    String errorBody =
        new ObjectMapper()
            .writeValueAsString(Map.of("error", DESTINATION + " " + CONTENT + " " + SECRET));
    expectSend(DESTINATION, CONTENT)
        .andRespond(
            withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorBody));

    assertThat(client.sendText(DESTINATION, CONTENT)).isFalse();
    assertPrivateLogsAbsent();
    assertThat(events)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getMessage().getFormattedMessage())
                  .isEqualTo("Failed to send LXMF message (" + status + ")");
            });
    server.verify();
  }

  @Test
  void connectionFailuresKeepSafeDiagnosticsWithoutExceptionText() throws Exception {
    expectSend(DESTINATION, CONTENT)
        .andRespond(withException(new IOException(DESTINATION + " " + CONTENT + " " + SECRET)));

    assertThat(client.sendText(DESTINATION, CONTENT)).isFalse();
    assertPrivateLogsAbsent();
    assertThat(events)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getMessage().getFormattedMessage())
                  .isEqualTo("Failed to send LXMF message (ResourceAccessException)");
            });
    server.verify();
  }

  private org.springframework.test.web.client.ResponseActions expectSend(
      String destination, String content) throws Exception {
    return server
        .expect(requestTo(URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            content()
                .json(
                    new ObjectMapper()
                        .writeValueAsString(
                            Map.of("destination_hash", destination, "content", content))));
  }

  private void assertPrivateLogsAbsent() {
    assertThat(events)
        .allSatisfy(
            event -> {
              assertThat(event.getMessage().getFormattedMessage())
                  .doesNotContain(DESTINATION, "PRIVATE_LXMF_MESSAGE", SECRET, "FORGED_LOG_ENTRY");
              assertThat(event.getMessage().getParameters())
                  .satisfies(
                      parameters ->
                          assertThat(java.util.Arrays.deepToString(parameters))
                              .doesNotContain(
                                  DESTINATION, "PRIVATE_LXMF_MESSAGE", SECRET, "FORGED_LOG_ENTRY"));
              assertThat(event.getThrown()).isNull();
              assertThat(event.getThrownProxy()).isNull();
            });
  }
}

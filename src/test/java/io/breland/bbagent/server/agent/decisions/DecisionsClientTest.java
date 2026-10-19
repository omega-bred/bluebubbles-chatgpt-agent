package io.breland.bbagent.server.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.breland.bbagent.server.agent.decisions.DecisionsClient.Operation;
import io.breland.bbagent.server.agent.decisions.DecisionsClient.Question;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DecisionsClientTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final DecisionsProperties properties = new DecisionsProperties();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private HttpServer server;
  private int status = 200;
  private long delayMillis;
  private String body =
      "{\"answers\":[{\"type\":\"predicate\",\"name\":\"test\",\"probability\":0.9}]}";

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/decisions",
        exchange -> {
          calls.incrementAndGet();
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          try {
            Thread.sleep(delayMillis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] response = body.getBytes(StandardCharsets.UTF_8);
          try {
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    registry.close();
  }

  private DecisionsClient client() {
    return new DecisionsClient(
        properties,
        mapper,
        new OperationalMetricsService(registry),
        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
        "test-key");
  }

  private DecisionsClient.Result evaluate(DecisionsClient client) {
    return client.evaluate(
        Operation.MESSAGE,
        Map.of("message", "hello"),
        List.of(new Question("test", "A question?")));
  }

  @Test
  void sendsOpenAiPredicateProtocolUsingExistingProxyCredentials() throws Exception {
    assertThat(evaluate(client()).probability("test")).hasValue(0.9);
    var request = mapper.readTree(requestBody.get());
    assertThat(request.path("model").asText()).isEqualTo("openai/gpt-6-luna");
    assertThat(request.path("input").asText()).contains("hello");
    assertThat(request.path("questions").get(0).path("instructions").asText())
        .contains("untrusted evidence");
    assertThat(authorization.get()).isEqualTo("Bearer test-key");
    assertThat(
            registry
                .get("bbagent.decisions.request.count")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"answers\":null}",
        "{\"answers\":[{\"type\":\"predicate\",\"name\":\"test\",\"probability\":1.1}]}",
        "{\"answers\":[{\"type\":\"predicate\",\"name\":\"test\",\"probability\":\"0.9\"}]}",
        "{\"answers\":[{\"type\":\"predicate\",\"name\":\"wrong\",\"probability\":0.9}]}",
        "{\"answers\":[{\"type\":\"predicate\",\"name\":\"test\",\"probability\":0.9},{\"type\":\"predicate\",\"name\":\"test\",\"probability\":0.8}]}"
      })
  void rejectsMalformedAnswers(String response) {
    body = response;
    assertThat(evaluate(client()).probabilities()).isEmpty();
  }

  @Test
  void refusalAndMissingAnswersAreUnknownRatherThanFalse() {
    body = "{\"answers\":[{\"type\":\"refusal\",\"name\":\"test\"}]}";
    var result = evaluate(client());
    assertThat(result.probability("test")).isEmpty();
    assertThat(result.atMost("test", 0.1)).isFalse();
    assertThat(result.atLeast("test", 0.9)).isFalse();
    assertThat(result.atMost("missing", 0.1)).isFalse();
  }

  @Test
  void mapsAnswersByNameNotPosition() {
    body =
        "{\"answers\":[{\"type\":\"predicate\",\"name\":\"b\",\"probability\":0.2},{\"type\":\"predicate\",\"name\":\"a\",\"probability\":0.8}]}";
    var result =
        client()
            .evaluate(
                Operation.MEMORY,
                "evidence",
                List.of(new Question("a", "A?"), new Question("b", "B?")));
    assertThat(result.probability("a")).hasValue(0.8);
    assertThat(result.probability("b")).hasValue(0.2);
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 404, 429, 500})
  void providerFailuresBackOffWithoutRepeatedRequests(int code) {
    status = code;
    var client = client();
    assertThat(evaluate(client).probabilities()).isEmpty();
    assertThat(evaluate(client).probabilities()).isEmpty();
    assertThat(calls.get()).isEqualTo(1);
    assertThat(
            registry
                .get("bbagent.decisions.request.count")
                .tag("outcome", "backoff")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void timeoutReturnsUnknown() {
    properties.setTimeout(Duration.ofMillis(100));
    delayMillis = 500;
    assertThat(evaluate(client()).probabilities()).isEmpty();
  }

  @Test
  void oversizedEvidenceIsNotTruncatedIntoAMisleadingDecision() {
    properties.setMaxInputCharacters(5);
    assertThat(evaluate(client()).probabilities()).isEmpty();
    assertThat(calls.get()).isZero();
  }

  @Test
  void disabledDoesNotCallProvider() {
    properties.setEnabled(false);
    assertThat(evaluate(client()).probabilities()).isEmpty();
    assertThat(calls.get()).isZero();
  }
}

package io.breland.bbagent.server.agent.tools.giphy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@ExtendWith(OutputCaptureExtension.class)
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class GiphyClientLoggingTest {
  private static final String API_KEY = "dummy-private-api-key";
  private static final String QUERY = "private-search-query";
  private static final String DOWNLOAD_URL =
      "https://media.giphy.invalid/private-gif.gif?token=dummy-private-download-token";
  private static final String ERROR_DETAIL = "private-provider-error-detail";

  private Object logger;
  private Object context;
  private Object configuration;
  private Map<String, ?> originalAppenders;
  private Map<String, ?> originalLoggers;
  private Object appender;
  private Object previousLevel;

  @BeforeEach
  void captureWarnings() throws Exception {
    // Core is already a runtime dependency. Reflective setup avoids adding a test dependency
    // and temporarily replaces only this logger's configuration, restoring it after each test.
    context =
        ReflectionTestUtils.invokeMethod(
            Class.forName("org.apache.logging.log4j.LogManager"), "getContext", false);
    configuration = ReflectionTestUtils.invokeMethod(context, "getConfiguration");
    Map<String, ?> appenders = ReflectionTestUtils.invokeMethod(configuration, "getAppenders");
    Map<String, ?> loggers = ReflectionTestUtils.invokeMethod(configuration, "getLoggers");
    originalAppenders = Map.copyOf(appenders);
    originalLoggers = Map.copyOf(loggers);
    logger = ReflectionTestUtils.invokeMethod(context, "getLogger", GiphyClient.class.getName());
    previousLevel = ReflectionTestUtils.invokeMethod(logger, "getLevel");
    Object layout =
        ReflectionTestUtils.invokeMethod(
            Class.forName("org.apache.logging.log4j.core.layout.PatternLayout"), "newBuilder");
    ReflectionTestUtils.invokeMethod(layout, "withPattern", "%p %m%n%ex{full}");
    Object builder =
        ReflectionTestUtils.invokeMethod(
            Class.forName("org.apache.logging.log4j.core.appender.ConsoleAppender"), "newBuilder");
    ReflectionTestUtils.invokeMethod(builder, "setName", "giphy-warning-capture");
    ReflectionTestUtils.invokeMethod(builder, "setFollow", true);
    Object patternLayout = ReflectionTestUtils.invokeMethod(layout, "build");
    ReflectionTestUtils.invokeMethod(builder, "setLayout", patternLayout);
    appender = ReflectionTestUtils.invokeMethod(builder, "build");
    ReflectionTestUtils.invokeMethod(appender, "start");
    Class<?> levelType = Class.forName("org.apache.logging.log4j.Level");
    Object captureConfig =
        Class.forName("org.apache.logging.log4j.core.config.LoggerConfig")
            .getConstructor(String.class, levelType, boolean.class)
            .newInstance(GiphyClient.class.getName(), levelType.getField("WARN").get(null), false);
    ReflectionTestUtils.invokeMethod(captureConfig, "addAppender", appender, null, null);
    ReflectionTestUtils.invokeMethod(configuration, "removeLogger", GiphyClient.class.getName());
    ReflectionTestUtils.invokeMethod(
        configuration, "addLogger", GiphyClient.class.getName(), captureConfig);
    ReflectionTestUtils.invokeMethod(context, "updateLoggers");
  }

  @AfterEach
  void restoreLogger() {
    if (appender != null) {
      ReflectionTestUtils.invokeMethod(configuration, "removeLogger", GiphyClient.class.getName());
      Object original = originalLoggers.get(GiphyClient.class.getName());
      if (original != null) {
        ReflectionTestUtils.invokeMethod(
            configuration, "addLogger", GiphyClient.class.getName(), original);
      }
      ReflectionTestUtils.invokeMethod(context, "updateLoggers");
      ReflectionTestUtils.invokeMethod(logger, "setLevel", previousLevel);
      ReflectionTestUtils.invokeMethod(appender, "stop");
      appender = null;
    }
  }

  @Test
  void captureLeavesNoConfigurationBehind() {
    restoreLogger();
    Map<String, ?> appenders = ReflectionTestUtils.invokeMethod(configuration, "getAppenders");
    Map<String, ?> loggers = ReflectionTestUtils.invokeMethod(configuration, "getLoggers");
    assertThat(appenders).isEqualTo(originalAppenders);
    assertThat(loggers).isEqualTo(originalLoggers);
    Object restoredLevel = ReflectionTestUtils.invokeMethod(logger, "getLevel");
    assertThat(restoredLevel).isEqualTo(previousLevel);
  }

  @ParameterizedTest
  @ValueSource(strings = {"message", "cause", "suppressed"})
  void searchTransportWarningsExcludePrivateData(String representation, CapturedOutput output) {
    GiphyClient client = failingClient(representation);

    assertThat(client.searchGifs(QUERY, 5, null, "en")).isEmpty();
    assertSafeWarning(output, "search", "WebClientRequestException");
  }

  @ParameterizedTest
  @ValueSource(strings = {"message", "cause", "suppressed"})
  void downloadTransportWarningsExcludePrivateData(String representation, CapturedOutput output) {
    GiphyClient client = failingClient(representation);

    assertThat(client.downloadGifBytes(DOWNLOAD_URL)).isEmpty();
    assertSafeWarning(output, "download", "WebClientRequestException");
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 429, 500})
  void downloadHttpWarningsExcludePrivateData(int status, CapturedOutput output) {
    GiphyClient client = respondingClient(HttpStatus.valueOf(status), ERROR_DETAIL, null);

    assertThat(client.downloadGifBytes(DOWNLOAD_URL)).isEmpty();
    assertSafeWarning(output, "download", Integer.toString(status));
  }

  @Test
  void searchDecodeWarningsExcludePrivateData(CapturedOutput output) {
    GiphyClient client = respondingClient(HttpStatus.OK, ERROR_DETAIL, null);

    assertThat(client.searchGifs(QUERY, 5, null, "en")).isEmpty();
    assertSafeWarning(output, "search", "DecodingException");
  }

  @Test
  void searchUriWarningsExcludePrivateData(CapturedOutput output) {
    GiphyClient client = failingClient("message");

    assertThat(client.searchGifs(QUERY + " {missing}", 5, null, "en")).isEmpty();
    assertSafeWarning(output, "search", "IllegalArgumentException");
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 429, 500})
  void searchHttpFailuresRemainSilentAndEmpty(int status, CapturedOutput output) {
    GiphyClient client = respondingClient(HttpStatus.valueOf(status), ERROR_DETAIL, null);

    assertThat(client.searchGifs(QUERY, 5, null, "en")).isEmpty();
    assertThat(output.getAll()).doesNotContain("Failed to search giphy", QUERY, ERROR_DETAIL);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 30})
  void searchRequestsKeepAuthenticationLimitsAndLanguageDefaults(int limit) {
    AtomicReference<MockClientHttpRequest> request = new AtomicReference<>();
    GiphyClient client = respondingClient(HttpStatus.OK, "{\"data\":[]}", request);

    assertThat(client.searchGifs(QUERY, limit, "pg", " ")).isEmpty();
    assertThat(request.get().getMethod()).isEqualTo(HttpMethod.GET);
    assertThat(request.get().getURI())
        .isEqualTo(
            URI.create(
                "https://giphy.invalid/v1/gifs/search?api_key="
                    + API_KEY
                    + "&q="
                    + QUERY
                    + "&limit="
                    + (limit == 0 ? 1 : 25)
                    + "&offset=0&bundle=messaging_non_clips&rating=pg&lang=en"));
    assertThat(request.get().getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
  }

  @Test
  void downloadsKeepTheExactUrlHeadersAndBytes() {
    AtomicReference<MockClientHttpRequest> request = new AtomicReference<>();
    GiphyClient client = respondingClient(HttpStatus.OK, "GIF89a", request);

    assertThat(client.downloadGifBytes(DOWNLOAD_URL))
        .hasValue("GIF89a".getBytes(StandardCharsets.UTF_8));
    assertThat(request.get().getMethod()).isEqualTo(HttpMethod.GET);
    assertThat(request.get().getURI()).isEqualTo(URI.create(DOWNLOAD_URL));
    assertThat(request.get().getHeaders().getAccept()).containsExactly(MediaType.ALL);
  }

  @Test
  void emptyDownloadsRemainEmpty() {
    assertThat(respondingClient(HttpStatus.OK, "", null).downloadGifBytes(DOWNLOAD_URL)).isEmpty();
    assertThat(respondingClient(HttpStatus.NO_CONTENT, "", null).downloadGifBytes(DOWNLOAD_URL))
        .isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " \t"})
  void nullOrBlankInputsNeverReachTheTransport(String input) {
    GiphyClient client =
        client(
            API_KEY,
            (method, uri, callback) -> {
              throw new AssertionError("Unexpected request for invalid input");
            });

    assertThat(client.searchGifs(input, 5, null, "en")).isEmpty();
    assertThat(client.downloadGifBytes(input)).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " \t"})
  void nullOrBlankKeysNeverReachTheTransport(String key, CapturedOutput output) {
    GiphyClient client =
        client(
            key,
            (method, uri, callback) -> {
              throw new AssertionError("Unexpected request without an API key");
            });

    assertThat(client.searchGifs(QUERY, 5, null, "en")).isEmpty();
    assertThat(output.getAll()).contains("Giphy API key not configured").doesNotContain(QUERY);
  }

  private void assertSafeWarning(CapturedOutput output, String operation, String diagnostic) {
    assertThat(output.getAll())
        .contains("WARN Failed to " + operation + " giphy")
        .doesNotContain(
            API_KEY,
            QUERY,
            DOWNLOAD_URL,
            "dummy-private-download-token",
            ERROR_DETAIL,
            "Caused by:",
            "Suppressed:",
            "at io.breland.");
    assertThat(output.getAll())
        .contains("WARN Failed to " + operation + " giphy (" + diagnostic + ")");
  }

  private GiphyClient failingClient(String representation) {
    return client(
        API_KEY,
        (method, uri, callback) -> {
          String detail = ERROR_DETAIL + " " + uri;
          IOException failure =
              new IOException(representation.equals("message") ? detail : "Transport failed");
          if (representation.equals("cause")) {
            failure.initCause(new IOException(detail));
          } else if (representation.equals("suppressed")) {
            failure.addSuppressed(new IOException(detail));
          }
          return callback.apply(new MockClientHttpRequest(method, uri)).then(Mono.error(failure));
        });
  }

  private GiphyClient respondingClient(
      HttpStatus status, String body, AtomicReference<MockClientHttpRequest> capturedRequest) {
    return client(
        API_KEY,
        (method, uri, callback) -> {
          MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
          if (capturedRequest != null) {
            capturedRequest.set(request);
          }
          MockClientHttpResponse response = new MockClientHttpResponse(status);
          response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
          response.setBody(body);
          return callback.apply(request).thenReturn(response);
        });
  }

  private GiphyClient client(String key, ClientHttpConnector connector) {
    GiphyClient client = new GiphyClient(key, "https://giphy.invalid/v1", new ObjectMapper());
    WebClient configured = (WebClient) ReflectionTestUtils.getField(client, "webClient");
    assertThat(configured).isNotNull();
    // Replace only the transport, retaining production codecs without sending network requests.
    ReflectionTestUtils.setField(
        client, "webClient", configured.mutate().clientConnector(connector).build());
    return client;
  }
}

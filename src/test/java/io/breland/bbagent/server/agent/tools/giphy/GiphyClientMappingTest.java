package io.breland.bbagent.server.agent.tools.giphy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient.GiphyGif;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

class GiphyClientMappingTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void preferredUrlsWinWithoutTrimming() throws Exception {
    assertThat(
            searchImages(
                """
                {
                  "original": {"url": " preferred.gif "},
                  "fixed_width": {"url": "fallback.gif"},
                  "fixed_width_still": {"url": " preferred.png "},
                  "downsized_still": {"url": "fallback.png"}
                }
                """))
        .containsExactly(new GiphyGif("gif-id", "title", " preferred.gif ", " preferred.png "));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "null",
        "{}",
        "[]",
        "false",
        "{\"url\":null}",
        "{\"url\":\"\"}",
        "{\"url\":\" \\t\\n\\u2003\"}"
      })
  void unusablePreferredImagesSelectFallbacks(String preferredJson) throws Exception {
    ObjectNode images = mapper.createObjectNode();
    if (preferredJson != null) {
      JsonNode preferred = mapper.readTree(preferredJson);
      images.set("original", preferred);
      images.set("fixed_width_still", preferred);
    }
    images.putObject("fixed_width").put("url", "fallback.gif");
    images.putObject("downsized_still").put("url", "fallback.png");

    assertThat(searchImages(images.toString()))
        .containsExactly(new GiphyGif("gif-id", "title", "fallback.gif", "fallback.png"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"null", "{}", "[]", "{\"url\":null}", "{\"url\":\"\"}", "{\"url\":\" \\t\"}"})
  void unusableAnimatedFallbacksOmitGifEvenWithAStill(String fallbackJson) throws Exception {
    ObjectNode images = mapper.createObjectNode();
    images.set("fixed_width", mapper.readTree(fallbackJson));
    images.putObject("fixed_width_still").put("url", "still.png");

    assertThat(searchImages(images.toString())).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "fallback.png"})
  void stillFallbacksKeepNullOrBlankValues(String stillUrl) throws Exception {
    ObjectNode images = mapper.createObjectNode();
    images.putObject("original").put("url", "animated.gif");
    images.putObject("fixed_width_still").put("url", " ");
    images.putObject("downsized_still").put("url", stillUrl);

    assertThat(searchImages(images.toString()))
        .containsExactly(new GiphyGif("gif-id", "title", "animated.gif", stillUrl));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " \t"})
  void absentOrNullFallbackImagesKeepTheBlankPreferredStill(String stillUrl) throws Exception {
    ObjectNode images = mapper.createObjectNode();
    images.putObject("original").put("url", "animated.gif");
    images.putObject("fixed_width_still").put("url", stillUrl);
    GiphyGif expected = new GiphyGif("gif-id", "title", "animated.gif", stillUrl);

    assertThat(searchImages(images.toString())).containsExactly(expected);
    images.putNull("downsized_still");
    assertThat(searchImages(images.toString())).containsExactly(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "{}", "[]", "{\"data\":null}", "{\"data\":{}}", "{\"data\":[]}"})
  void missingOrNonArrayDataReturnsNoGifs(String json) {
    assertThat(search(json)).isEmpty();
  }

  @Test
  void mixedResultsKeepOrderDefaultsAndScalarUrlConversion() {
    assertThat(
            search(
                """
                {"data":[
                  null, {}, {"images":null},
                  {"images":{"original":{"url":"first.gif"}}},
                  {"id":"second","title":"Second", "images":{
                    "original":{"url":123},
                    "fixed_width_still":{"url":true}
                  }}
                ]}
                """))
        .containsExactly(
            new GiphyGif(null, "", "first.gif", null),
            new GiphyGif("second", "Second", "123", "true"));
  }

  private List<GiphyGif> searchImages(String imagesJson) throws Exception {
    ObjectNode response = mapper.createObjectNode();
    response
        .putArray("data")
        .addObject()
        .put("id", "gif-id")
        .put("title", "title")
        .set("images", mapper.readTree(imagesJson));
    return search(response.toString());
  }

  private List<GiphyGif> search(String json) {
    GiphyClient client = new GiphyClient("test-key", "https://giphy.invalid/v1", mapper);
    WebClient configuredClient = (WebClient) ReflectionTestUtils.getField(client, "webClient");
    assertThat(configuredClient).isNotNull();
    // Keep the production codecs and replace only the transport; no network requests are sent.
    ReflectionTestUtils.setField(
        client,
        "webClient",
        configuredClient
            .mutate()
            .clientConnector(
                (method, uri, requestCallback) -> {
                  MockClientHttpResponse response = new MockClientHttpResponse(HttpStatus.OK);
                  response.getHeaders().set("Content-Type", "application/json");
                  response.setBody(json);
                  return requestCallback
                      .apply(new MockClientHttpRequest(method, uri))
                      .thenReturn(response);
                })
            .build());
    return client.searchGifs("query", 5, null, "en");
  }
}

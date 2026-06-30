package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ContactApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1ICloudApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1MessageApi;
import io.breland.bbagent.generated.bluebubblesclient.model.AddressEntry;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiResponseFindMyFriendsLocations;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiResponseGetChat;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ContactGet200Response;
import io.breland.bbagent.generated.bluebubblesclient.model.Contact;
import io.breland.bbagent.generated.bluebubblesclient.model.FindMyFriendLocation;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.metrics.OperationalMetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class BBHttpClientWrapperTest {

  @Test
  void getFindMyLocationRefreshesAndReturnsMatchingUserLocation() {
    V1ICloudApi icloudApi = Mockito.mock(V1ICloudApi.class);
    BBHttpClientWrapper wrapper = wrapper(icloudApi);
    FindMyFriendLocation bob = location("bob@example.com");
    FindMyFriendLocation alice = location("+15555550123");

    when(icloudApi.apiV1IcloudFindmyFriendsRefreshPost("pw"))
        .thenReturn(Mono.just(success(List.of(bob, alice))));

    FindMyFriendLocation result = wrapper.getFindMyLocation("tel:+1 (555) 555-0123");

    assertEquals(alice, result);
    verify(icloudApi).apiV1IcloudFindmyFriendsRefreshPost("pw");
    verify(icloudApi, never()).apiV1IcloudFindmyFriendsGet(anyString());
  }

  @Test
  void getFindMyLocationReturnsNullWhenUserIsMissing() {
    V1ICloudApi icloudApi = Mockito.mock(V1ICloudApi.class);
    BBHttpClientWrapper wrapper = wrapper(icloudApi);

    when(icloudApi.apiV1IcloudFindmyFriendsRefreshPost("pw"))
        .thenReturn(Mono.just(success(List.of(location("bob@example.com")))));

    assertNull(wrapper.getFindMyLocation("alice@example.com"));
  }

  @Test
  void getFindMyLocationCanMatchFallbackEmailAfterRefreshingOnce() {
    V1ICloudApi icloudApi = Mockito.mock(V1ICloudApi.class);
    BBHttpClientWrapper wrapper = wrapper(icloudApi);
    FindMyFriendLocation alice = location("alice@example.com");

    when(icloudApi.apiV1IcloudFindmyFriendsRefreshPost("pw"))
        .thenReturn(Mono.just(success(List.of(alice))));

    FindMyFriendLocation result =
        wrapper.getFindMyLocation(List.of("tel:+1 (555) 555-0123", "mailto:alice@example.com"));

    assertEquals(alice, result);
    verify(icloudApi, times(1)).apiV1IcloudFindmyFriendsRefreshPost("pw");
  }

  @Test
  void getFindMyLocationRequiresSuccessfulBlueBubblesResponse() {
    V1ICloudApi icloudApi = Mockito.mock(V1ICloudApi.class);
    BBHttpClientWrapper wrapper = wrapper(icloudApi);

    when(icloudApi.apiV1IcloudFindmyFriendsRefreshPost("pw"))
        .thenReturn(
            Mono.just(
                ApiResponseFindMyFriendsLocations.builder()
                    .status(500)
                    .message("Failed to fetch Find My friends locations")
                    .data(List.of())
                    .build()));

    assertThrows(IllegalStateException.class, () -> wrapper.getFindMyLocation("alice@example.com"));
  }

  @Test
  void getFindMyLocationRecordsBlueBubblesOperationMetrics() {
    V1ICloudApi icloudApi = Mockito.mock(V1ICloudApi.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    BBHttpClientWrapper wrapper =
        new BBHttpClientWrapper(
            "pw",
            Mockito.mock(V1MessageApi.class),
            Mockito.mock(V1ContactApi.class),
            icloudApi,
            new OperationalMetricsService(registry));

    when(icloudApi.apiV1IcloudFindmyFriendsRefreshPost("pw"))
        .thenReturn(Mono.just(success(List.of(location("alice@example.com")))));

    wrapper.getFindMyLocation("alice@example.com");

    assertEquals(
        1.0,
        registry
            .get("bbagent.bluebubbles.operation.count")
            .tag("operation", "refresh_find_my_locations")
            .tag("outcome", "success")
            .tag("failure_type", "none")
            .counter()
            .count());
    assertEquals(
        1L,
        registry
            .get("bbagent.bluebubbles.operation.duration")
            .tag("operation", "refresh_find_my_locations")
            .tag("outcome", "success")
            .tag("failure_type", "none")
            .timer()
            .count());
  }

  @Test
  void getContactAddressesForReturnsPhoneAndEmailAliasesFromMatchingContact() {
    V1ContactApi contactApi = Mockito.mock(V1ContactApi.class);
    BBHttpClientWrapper wrapper =
        new BBHttpClientWrapper("pw", Mockito.mock(V1MessageApi.class), contactApi);

    when(contactApi.apiV1ContactGet("pw"))
        .thenReturn(
            Mono.just(
                ApiV1ContactGet200Response.builder()
                    .status(200)
                    .message("Successfully fetched contacts")
                    .data(
                        List.of(
                            new Contact()
                                .phoneNumbers(
                                    List.of(new AddressEntry().address("+1 (555) 555-0123")))
                                .emails(List.of(new AddressEntry().address("alice@example.com")))))
                    .build()));

    assertEquals(
        List.of("+1 (555) 555-0123", "alice@example.com"),
        wrapper.getContactAddressesFor("tel:555-555-0123"));
  }

  @Test
  void findMyFriendsResponseDeserializesBooleanLocatingFlag() throws Exception {
    String json =
        """
        {
          "status": 200,
          "message": "Successfully refreshed Find My friends locations",
          "data": [
            {
              "handle": "+15555550123",
              "coordinates": [37.33182, -122.03118],
              "long_address": "1 Apple Park Way, Cupertino, CA 95014, United States",
              "short_address": "Apple Park",
              "subtitle": "Apple Park",
              "title": "+15555550123",
              "last_updated": 1777050691000,
              "is_locating_in_progress": false,
              "status": "shallow"
            }
          ]
        }
        """;

    ApiResponseFindMyFriendsLocations response =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(json, ApiResponseFindMyFriendsLocations.class);

    assertEquals(false, response.getData().getFirst().getIsLocatingInProgress());
  }

  @Test
  void getChatResponseDeserializesAttachmentStyleGroupPhotoGuid() throws Exception {
    String groupPhotoGuid = "at_0_7E65761D-1B84-4FAF-BC17-7F485E7FDEC5";
    String json =
        """
        {
          "status": 200,
          "message": "Successfully fetched chat",
          "data": {
            "guid": "any;+;chat193898160757775814",
            "participants": [],
            "properties": [
              {
                "groupPhotoGuid": "%s"
              }
            ]
          }
        }
        """
            .formatted(groupPhotoGuid);

    ApiResponseGetChat response =
        new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, ApiResponseGetChat.class);

    assertEquals(groupPhotoGuid, response.getData().getProperties().getFirst().getGroupPhotoGuid());
  }

  @Test
  void getMessagesInChatAllowsLargeBlueBubblesResponses() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String largeText = "x".repeat(300 * 1024);
    byte[] response =
        """
        {"status":200,"message":"ok","data":[{"guid":"message-guid","text":"%s","isFromMe":true}]}
        """
            .formatted(largeText)
            .getBytes(StandardCharsets.UTF_8);
    server.createContext(
        "/api/v1/chat/chat-guid/message",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();

    try {
      BBHttpClientWrapper wrapper =
          new BBHttpClientWrapper(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "pw",
              30,
              new ObjectMapper(),
              null);

      assertEquals(largeText, wrapper.getMessagesInChat("chat-guid").getFirst().getText());
    } finally {
      server.stop(0);
    }
  }

  private static BBHttpClientWrapper wrapper(V1ICloudApi icloudApi) {
    return new BBHttpClientWrapper(
        "pw", Mockito.mock(V1MessageApi.class), Mockito.mock(V1ContactApi.class), icloudApi);
  }

  private static ApiResponseFindMyFriendsLocations success(List<FindMyFriendLocation> locations) {
    return ApiResponseFindMyFriendsLocations.builder()
        .status(200)
        .message("Successfully refreshed Find My friends locations")
        .data(locations)
        .build();
  }

  private static FindMyFriendLocation location(String handle) {
    return FindMyFriendLocation.builder()
        .handle(handle)
        .coordinates(List.of(37.33182, -122.03118))
        .longAddress("1 Apple Park Way, Cupertino, CA 95014, United States")
        .shortAddress("Apple Park")
        .subtitle("Apple Park")
        .title(handle)
        .lastUpdated(1777050691000L)
        .isLocatingInProgress(false)
        .status(FindMyFriendLocation.StatusEnum.SHALLOW)
        .build();
  }
}

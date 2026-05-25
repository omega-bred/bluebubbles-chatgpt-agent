package io.breland.bbagent.server.agent.tools.reservations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.AvailabilityRequest;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.BookingRequest;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.SearchRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestaurantReservationServiceTest {

  @Test
  void reportsProviderCapabilitiesWithoutCredentials() {
    RestaurantReservationService service = service();

    var response = service.capabilities();

    assertEquals("ok", response.status());
    assertFalse(response.providers().get(0).apiConfigured());
    assertEquals("opentable", response.providers().get(0).provider());
    assertEquals("resy", response.providers().get(1).provider());
    assertFalse(response.providers().get(1).bookingApiSupported());
    assertTrue(response.providers().get(1).loginUrl().contains("resy.com"));
  }

  @Test
  void searchFallsBackToProviderLinksWhenApisAreNotConfigured() {
    RestaurantReservationService service = service();

    var response =
        service.searchRestaurants(
            new SearchRequest("sushi", "San Francisco", "all", "2026-06-01T19:00:00", 2, 5));

    assertEquals("ok", response.status());
    assertTrue(response.results().isEmpty());
    assertEquals(2, response.links().size());
    assertTrue(response.links().stream().anyMatch(link -> "opentable".equals(link.provider())));
    assertTrue(response.links().stream().anyMatch(link -> "resy".equals(link.provider())));
    assertTrue(response.notes().stream().anyMatch(note -> note.contains("not configured")));
  }

  @Test
  void opentableAvailabilityRequiresRestaurantIdOrHandoff() {
    RestaurantReservationService service = service();

    var response =
        service.checkAvailability(
            new AvailabilityRequest(
                "opentable",
                null,
                "State Bird Provisions",
                "San Francisco",
                "2026-06-01T19:00:00",
                2,
                null,
                null,
                null,
                null));

    assertEquals("handoff_required", response.status());
    assertEquals("opentable", response.provider());
    assertFalse(response.links().isEmpty());
    assertTrue(response.notes().get(0).contains("rid"));
  }

  @Test
  void opentableAvailabilityUsesConfiguredBaseUrlForRelativePaths() throws Exception {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    List<String> requestUris = Collections.synchronizedList(new ArrayList<>());
    List<String> authHeaders = Collections.synchronizedList(new ArrayList<>());
    server.createContext(
        "/api/v2/oauth/token",
        exchange -> {
          requestUris.add(exchange.getRequestURI().toString());
          writeJson(exchange, 200, "{\"access_token\":\"test-token\",\"expires_in\":3600}");
        });
    server.createContext(
        "/v2/availability/123",
        exchange -> {
          requestUris.add(exchange.getRequestURI().toString());
          authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
          writeJson(exchange, 200, "{\"times_available\":[\"2026-06-01T19:00:00\"]}");
        });
    server.start();
    try {
      String baseUrl =
          "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
      RestaurantReservationProperties properties = new RestaurantReservationProperties();
      properties.getOpentable().setBaseUrl(baseUrl);
      properties.getOpentable().setOauthUrl(baseUrl);
      properties.getOpentable().setClientId("client-id");
      properties.getOpentable().setClientSecret("client-secret");
      RestaurantReservationService service = new RestaurantReservationService(properties);

      var response =
          service.checkAvailability(
              new AvailabilityRequest(
                  "opentable",
                  "123",
                  "State Bird Provisions",
                  "San Francisco",
                  "2026-06-01T19:00:00",
                  2,
                  null,
                  null,
                  null,
                  null));

      assertEquals("ok", response.status());
      assertEquals(1, response.slots().size());
      assertTrue(requestUris.stream().anyMatch(uri -> uri.startsWith("/v2/availability/123?")));
      assertEquals(List.of("Bearer test-token"), authHeaders);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void bookingRequiresExplicitConfirmationBeforeExternalAction() {
    RestaurantReservationService service = service();

    var response =
        service.makeReservation(
            new BookingRequest(
                "opentable",
                "123",
                "State Bird Provisions",
                "San Francisco",
                "2026-06-01T19:00:00",
                2,
                "Ada",
                "Lovelace",
                "ada@example.com",
                "1",
                "5551234567",
                "mobile",
                "standard",
                null,
                null,
                null,
                null,
                "Birthday",
                false,
                false));

    assertEquals("requires_confirmation", response.status());
    assertTrue(response.message().contains("explicitly confirm"));
    assertTrue(response.notes().stream().anyMatch(note -> note.contains("payment-card")));
  }

  @Test
  void resyBookingUsesHandoff() {
    RestaurantReservationService service = service();

    var response =
        service.makeReservation(
            new BookingRequest(
                "resy",
                null,
                "Via Carota",
                "New York",
                "2026-06-01T19:00:00",
                2,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                true));

    assertEquals("handoff_required", response.status());
    assertEquals("resy", response.provider());
    assertFalse(response.links().isEmpty());
    assertTrue(response.links().get(0).url().contains("resy.com"));
  }

  private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream responseBody = exchange.getResponseBody()) {
      responseBody.write(bytes);
    }
  }

  private RestaurantReservationService service() {
    return new RestaurantReservationService(new RestaurantReservationProperties());
  }
}

package io.breland.bbagent.server.agent.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NominatimReverseLocationLookupTest {
  @Test
  void reverseLookupCallsNominatimAndReturnsApproximateAddress() throws Exception {
    AtomicReference<String> requestUri = new AtomicReference<>();
    HttpServer server =
        jsonServer(
            200,
            """
            {
              "display_name": "Apple Park Way, Cupertino, Santa Clara County, California, United States",
              "address": {
                "road": "Apple Park Way",
                "city": "Cupertino",
                "county": "Santa Clara County",
                "state": "California",
                "country": "United States"
              }
            }
            """,
            requestUri);
    try {
      NominatimReverseLocationLookup lookup =
          new NominatimReverseLocationLookup(baseUrl(server), 2, 18);

      Optional<ReverseLocationLookupResult> result = lookup.reverseLookup(37.33182, -122.03118);

      assertTrue(result.isPresent());
      assertEquals(
          "Apple Park Way, Cupertino, Santa Clara County, California, United States",
          result.get().approximateAddress());
      assertTrue(requestUri.get().startsWith("/reverse?"));
      assertTrue(requestUri.get().contains("format=jsonv2"));
      assertTrue(requestUri.get().contains("lat=37.33182"));
      assertTrue(requestUri.get().contains("lon=-122.03118"));
      assertTrue(requestUri.get().contains("zoom=18"));
      assertTrue(requestUri.get().contains("addressdetails=1"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void reverseLookupBuildsApproximateAddressFromAddressComponents() throws Exception {
    HttpServer server =
        jsonServer(
            200,
            """
            {
              "address": {
                "road": "Market Street",
                "city": "San Francisco",
                "state": "California",
                "postcode": "94103",
                "country": "United States"
              }
            }
            """,
            new AtomicReference<>());
    try {
      NominatimReverseLocationLookup lookup =
          new NominatimReverseLocationLookup(baseUrl(server), 2, 18);

      Optional<ReverseLocationLookupResult> result = lookup.reverseLookup(37.7749, -122.4194);

      assertTrue(result.isPresent());
      assertEquals(
          "Market Street, San Francisco, California, 94103, United States",
          result.get().approximateAddress());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void reverseLookupReturnsEmptyWhenServiceErrors() throws Exception {
    HttpServer server = jsonServer(500, "{}", new AtomicReference<>());
    try {
      NominatimReverseLocationLookup lookup =
          new NominatimReverseLocationLookup(baseUrl(server), 2, 18);

      assertFalse(lookup.reverseLookup(37.33182, -122.03118).isPresent());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void reverseLookupReturnsEmptyWhenBaseUrlIsBlank() {
    NominatimReverseLocationLookup lookup = new NominatimReverseLocationLookup("", 2, 18);

    assertFalse(lookup.reverseLookup(37.33182, -122.03118).isPresent());
  }

  private static HttpServer jsonServer(
      int status, String responseBody, AtomicReference<String> requestUri) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/reverse",
        exchange -> {
          requestUri.set(exchange.getRequestURI().toString());
          byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, body.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    return server;
  }

  private static String baseUrl(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }
}

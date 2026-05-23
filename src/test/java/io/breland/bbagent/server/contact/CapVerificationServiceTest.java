package io.breland.bbagent.server.contact;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class CapVerificationServiceTest {

  @Test
  void verifiesTokenAgainstSiteScopedSiteverifyEndpoint() throws IOException {
    AtomicReference<String> requestPath = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/contact-site/siteverify",
        exchange -> {
          requestPath.set(exchange.getRequestURI().getPath());
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      ContactProperties properties = new ContactProperties();
      properties.setCapBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
      properties.setCapSiteKey("contact-site");
      properties.setCapSecretKey("cap-secret");
      CapVerificationService service = new CapVerificationService(properties, WebClient.builder());

      assertThat(service.apiEndpoint())
          .isEqualTo("http://127.0.0.1:" + server.getAddress().getPort() + "/contact-site/");
      assertThat(service.verify("cap-token")).isTrue();

      assertThat(requestPath.get()).isEqualTo("/contact-site/siteverify");
      assertThat(requestBody.get()).contains("\"secret\":\"cap-secret\"");
      assertThat(requestBody.get()).contains("\"response\":\"cap-token\"");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsBlankTokenWithoutCallingCap() {
    ContactProperties properties = new ContactProperties();
    properties.setCapBaseUrl("https://cap.example");
    properties.setCapSiteKey("contact-site");
    properties.setCapSecretKey("cap-secret");
    CapVerificationService service = new CapVerificationService(properties, WebClient.builder());

    assertThat(service.verify(" ")).isFalse();
  }
}

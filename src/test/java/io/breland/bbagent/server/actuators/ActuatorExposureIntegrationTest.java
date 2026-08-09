package io.breland.bbagent.server.actuators;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.boot.admin.client.enabled=false")
@AutoConfigureTestRestTemplate
class ActuatorExposureIntegrationTest {
  private static final Properties PRODUCTION_PROPERTIES = loadProductionProperties();

  @Autowired private TestRestTemplate restTemplate;

  @DynamicPropertySource
  static void productionManagementProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "management.endpoints.web.exposure.include",
        () -> PRODUCTION_PROPERTIES.getProperty("management.endpoints.web.exposure.include"));
    registry.add(
        "management.endpoint.health.show-details",
        () -> PRODUCTION_PROPERTIES.getProperty("management.endpoint.health.show-details"));
  }

  @Test
  void anonymousActuatorOnlyPublishesHealthAndInfo() {
    ResponseEntity<JsonNode> discovery = restTemplate.getForEntity("/actuator", JsonNode.class);
    ResponseEntity<JsonNode> health = restTemplate.getForEntity("/actuator/health", JsonNode.class);
    ResponseEntity<JsonNode> info = restTemplate.getForEntity("/actuator/info", JsonNode.class);

    assertThat(discovery.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(health.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(info.getStatusCode().is2xxSuccessful()).isTrue();

    Set<String> linkRelations = new TreeSet<>();
    linkRelations.addAll(discovery.getBody().path("_links").propertyNames());
    assertThat(linkRelations)
        .contains("self", "health", "info")
        .allMatch(
            relation ->
                relation.equals("self")
                    || relation.equals("info")
                    || relation.equals("health")
                    || relation.startsWith("health-"));
  }

  @Test
  void anonymousHealthDoesNotExposeComponentDetails() {
    ResponseEntity<JsonNode> response =
        restTemplate.getForEntity("/actuator/health", JsonNode.class);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody().has("status")).isTrue();
    assertThat(response.getBody().has("components")).isFalse();
  }

  private static Properties loadProductionProperties() {
    try {
      return PropertiesLoaderUtils.loadProperties(
          new FileSystemResource("src/main/resources/application.properties"));
    } catch (IOException e) {
      throw new IllegalStateException("Could not load production application properties", e);
    }
  }
}

package io.breland.bbagent.server.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ProductionManifestValidationTest {
  private static final Path COMPONENTS_MANIFEST =
      Path.of("manifests/bluebubbles-chatgpt-agent/be-components.yaml");

  @Test
  void productionWorkloadsDoNotDeclareDuplicateEnvironmentVariables() throws IOException {
    List<String> duplicates = new ArrayList<>();

    try (InputStream input = Files.newInputStream(COMPONENTS_MANIFEST)) {
      for (Object document : new Yaml().loadAll(input)) {
        if (!(document instanceof Map<?, ?> resource)) {
          continue;
        }
        Map<?, ?> podSpec = podSpec(resource);
        if (podSpec == null) {
          continue;
        }
        String resourceName = stringValue(nested(resource, "metadata", "name"));
        for (Map<?, ?> container : mapList(podSpec.get("containers"))) {
          String containerName = stringValue(container.get("name"));
          Set<String> names = new HashSet<>();
          for (Map<?, ?> environmentVariable : mapList(container.get("env"))) {
            String name = stringValue(environmentVariable.get("name"));
            if (!names.add(name)) {
              duplicates.add(resourceName + "/" + containerName + ": " + name);
            }
          }
        }
      }
    }

    assertThat(duplicates)
        .as("duplicate env names make Flux server-side apply reject the workload")
        .isEmpty();
  }

  private static Map<?, ?> podSpec(Map<?, ?> resource) {
    String kind = stringValue(resource.get("kind"));
    return switch (kind) {
      case "Deployment", "StatefulSet", "DaemonSet" ->
          mapValue(nested(resource, "spec", "template", "spec"));
      case "Job" -> mapValue(nested(resource, "spec", "template", "spec"));
      case "CronJob" ->
          mapValue(nested(resource, "spec", "jobTemplate", "spec", "template", "spec"));
      default -> null;
    };
  }

  private static Object nested(Map<?, ?> root, String... path) {
    Object value = root;
    for (String key : path) {
      if (!(value instanceof Map<?, ?> map)) {
        return null;
      }
      value = map.get(key);
    }
    return value;
  }

  private static Map<?, ?> mapValue(Object value) {
    return value instanceof Map<?, ?> map ? map : null;
  }

  private static List<Map<?, ?>> mapList(Object value) {
    if (!(value instanceof List<?> values)) {
      return List.of();
    }
    List<Map<?, ?>> maps = new ArrayList<>();
    for (Object item : values) {
      if (item instanceof Map<?, ?> map) {
        maps.add(map);
      }
    }
    return List.copyOf(maps);
  }

  private static String stringValue(Object value) {
    return value == null ? "<unnamed>" : value.toString();
  }
}

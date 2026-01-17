package io.breland.bbagent.server.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonSchemaUtilities {
  public static FunctionTool.Parameters jsonSchema(Map<String, Object> schema) {
    Map<String, Object> normalized = new LinkedHashMap<>(schema);
    normalized.putIfAbsent("additionalProperties", false);
    if (!normalized.containsKey("required")) {
      Object propertiesObj = normalized.get("properties");
      if (propertiesObj instanceof Map<?, ?> propertiesMap) {
        List<String> required = new ArrayList<>();
        for (Object key : propertiesMap.keySet()) {
          required.add(String.valueOf(key));
        }
        normalized.put("required", required);
      }
    }
    FunctionTool.Parameters.Builder builder = FunctionTool.Parameters.builder();
    for (Map.Entry<String, Object> entry : normalized.entrySet()) {
      builder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
    }
    return builder.build();
  }

  public static FunctionTool.Parameters jsonSchema(Class<?> schemaClass) {
    ResolvedSchema resolved = ModelConverters.getInstance().readAllAsResolvedSchema(schemaClass);
    Map<String, Object> schema =
        Json.mapper().convertValue(resolved.schema, new TypeReference<Map<String, Object>>() {});
    Map<String, Object> normalized = new LinkedHashMap<>(schema);
    normalized.putIfAbsent("type", "object");
    normalized.putIfAbsent("properties", new LinkedHashMap<>());
    normalized.putIfAbsent("additionalProperties", false);
    FunctionTool.Parameters.Builder builder = FunctionTool.Parameters.builder();
    for (Map.Entry<String, Object> entry : normalized.entrySet()) {
      builder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
    }
    return builder.build();
  }
}

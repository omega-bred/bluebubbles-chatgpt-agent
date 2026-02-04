package io.breland.bbagent.server.agent.cadence;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.serviceclient.ClientOptions;
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.worker.WorkerFactory;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import kotlin.Lazy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "agent.workflow", name = "mode", havingValue = "cadence")
public class CadenceWorkflowConfig {

  @Bean
  public WorkflowServiceTChannel cadenceService(AgentWorkflowProperties properties) {
    ClientOptions options =
        ClientOptions.newBuilder()
            .setHost(properties.getCadenceHost())
            .setPort(properties.getCadencePort())
            .build();
    return new WorkflowServiceTChannel(options);
  }

  @Bean
  public WorkflowClient cadenceWorkflowClient(
      WorkflowServiceTChannel cadenceService, AgentWorkflowProperties properties) {
    DataConverter dataConverter =
        new JsonDataConverter(
            builder ->
                builder
                    .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
                    .registerTypeHierarchyAdapter(JsonField.class, new JsonFieldTypeAdapter())
                    .registerTypeHierarchyAdapter(JsonValue.class, new JsonValueTypeAdapter())
                    .registerTypeHierarchyAdapter(Lazy.class, new LazyTypeAdapter()));
    WorkflowClientOptions options =
        WorkflowClientOptions.newBuilder()
            .setDomain(properties.getCadenceDomain())
            .setDataConverter(dataConverter)
            .build();
    return WorkflowClient.newInstance(cadenceService, options);
  }

  @Bean
  public WorkerFactory cadenceWorkerFactory(
      WorkflowClient cadenceWorkflowClient,
      AgentWorkflowProperties properties,
      CadenceAgentActivities activities) {
    WorkerFactory factory = WorkerFactory.newInstance(cadenceWorkflowClient);
    Worker worker = factory.newWorker(properties.getCadenceTaskList());
    worker.registerWorkflowImplementationTypes(CadenceMessageWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    factory.start();
    return factory;
  }

  static final class InstantTypeAdapter
      implements JsonSerializer<Instant>, JsonDeserializer<Instant> {
    @Override
    public JsonElement serialize(Instant src, Type typeOfSrc, JsonSerializationContext context) {
      if (src == null) {
        return JsonNull.INSTANCE;
      }
      return new JsonPrimitive(src.toString());
    }

    @Override
    public Instant deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
        throws JsonParseException {
      if (json == null || json.isJsonNull()) {
        return null;
      }
      if (json.isJsonPrimitive()) {
        JsonPrimitive primitive = json.getAsJsonPrimitive();
        if (primitive.isNumber()) {
          return Instant.ofEpochMilli(primitive.getAsLong());
        }
        String text = primitive.getAsString();
        if (text == null || text.isBlank()) {
          return null;
        }
        try {
          return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
          try {
            long millis = Long.parseLong(text);
            return Instant.ofEpochMilli(millis);
          } catch (NumberFormatException ignoredNumber) {
            return null;
          }
        }
      }
      throw new JsonParseException("Unsupported Instant value: " + json);
    }
  }

  static final class JsonFieldTypeAdapter
      implements JsonSerializer<JsonField<?>>, JsonDeserializer<JsonField<?>> {
    @Override
    public JsonElement serialize(
        JsonField<?> src, Type typeOfSrc, JsonSerializationContext context) {
      if (src == null || src.isMissing() || src.isNull()) {
        return JsonNull.INSTANCE;
      }
      if (src.asKnown().isPresent()) {
        return context.serialize(src.asKnown().get());
      }
      if (src.asUnknown().isPresent()) {
        return context.serialize(src.asUnknown().get());
      }
      return JsonNull.INSTANCE;
    }

    @Override
    public JsonField<?> deserialize(
        JsonElement json, Type typeOfT, JsonDeserializationContext context)
        throws JsonParseException {
      if (json == null || json.isJsonNull()) {
        return JsonField.ofNullable(null);
      }
      Object value = context.deserialize(json, Object.class);
      return JsonField.ofNullable(value);
    }
  }

  static final class JsonValueTypeAdapter
      implements JsonSerializer<JsonValue>, JsonDeserializer<JsonValue> {
    @Override
    public JsonElement serialize(JsonValue src, Type typeOfSrc, JsonSerializationContext context) {
      if (src == null) {
        return JsonNull.INSTANCE;
      }
      Object value = src.convert(Object.class);
      return context.serialize(value);
    }

    @Override
    public JsonValue deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
        throws JsonParseException {
      if (json == null || json.isJsonNull()) {
        return JsonValue.from(null);
      }
      Object value = context.deserialize(json, Object.class);
      return JsonValue.from(value);
    }
  }

  static final class LazyTypeAdapter implements JsonSerializer<Lazy<?>>, JsonDeserializer<Lazy<?>> {
    @Override
    public JsonElement serialize(Lazy<?> src, Type typeOfSrc, JsonSerializationContext context) {
      if (src == null) {
        return JsonNull.INSTANCE;
      }
      if (src.isInitialized()) {
        return context.serialize(src.getValue());
      }
      return JsonNull.INSTANCE;
    }

    @Override
    public Lazy<?> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
        throws JsonParseException {
      if (json == null || json.isJsonNull()) {
        return new SimpleLazy<>(null);
      }
      Object value = context.deserialize(json, Object.class);
      return new SimpleLazy<>(value);
    }
  }

  static final class SimpleLazy<T> implements Lazy<T> {
    private final T value;

    SimpleLazy(T value) {
      this.value = value;
    }

    @Override
    public T getValue() {
      return value;
    }

    @Override
    public boolean isInitialized() {
      return true;
    }
  }
}

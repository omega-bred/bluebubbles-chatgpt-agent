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
import com.uber.cadence.serviceclient.WorkflowServiceGrpc;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.worker.WorkerFactory;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import io.breland.bbagent.server.blobstore.BlobStore;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.Function;
import java.util.function.LongFunction;
import kotlin.Lazy;
import kotlin.LazyKt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class CadenceWorkflowConfig {

  @Bean
  public WorkflowServiceGrpc cadenceService(AgentWorkflowProperties properties) {
    ClientOptions options =
        ClientOptions.newBuilder()
            .setHost(properties.getCadenceHost())
            .setPort(properties.getCadencePort())
            .build();
    return new WorkflowServiceGrpc(options);
  }

  @Bean
  public WorkflowClient cadenceWorkflowClient(
      WorkflowServiceGrpc cadenceService, AgentWorkflowProperties properties, BlobStore blobs) {
    return workflowClient(
        cadenceService, properties, new CadenceBlobDataConverter(inlineConverter(), blobs));
  }

  private static DataConverter inlineConverter() {
    return new JsonDataConverter(
        builder ->
            builder
                .registerTypeAdapter(
                    Instant.class,
                    new TemporalTypeAdapter<>(Instant.class, Instant::parse, Instant::ofEpochMilli))
                .registerTypeAdapter(
                    Duration.class,
                    new TemporalTypeAdapter<>(Duration.class, Duration::parse, Duration::ofMillis))
                .registerTypeHierarchyAdapter(JsonField.class, new JsonFieldTypeAdapter())
                .registerTypeHierarchyAdapter(JsonValue.class, new JsonValueTypeAdapter())
                .registerTypeHierarchyAdapter(Lazy.class, new LazyTypeAdapter()));
  }

  private static WorkflowClient workflowClient(
      WorkflowServiceGrpc cadenceService,
      AgentWorkflowProperties properties,
      DataConverter converter) {
    WorkflowClientOptions options =
        WorkflowClientOptions.newBuilder()
            .setDomain(properties.getCadenceDomain())
            .setDataConverter(converter)
            .build();
    return WorkflowClient.newInstance(cadenceService, options);
  }

  @Bean
  @Profile("!test")
  public WorkerFactory cadenceWorkerFactory(
      WorkflowClient cadenceWorkflowClient,
      AgentWorkflowProperties properties,
      CadenceAgentActivities activities) {
    return startWorker(cadenceWorkflowClient, properties.getSharedCadenceTaskList(), activities);
  }

  @Bean
  @Profile("!test")
  public WorkerFactory legacyCadenceWorkerFactory(
      WorkflowServiceGrpc cadenceService,
      AgentWorkflowProperties properties,
      CadenceAgentActivities activities) {
    // Old workers can still poll this list during a rolling update and expect inline JSON.
    return startWorker(
        workflowClient(cadenceService, properties, inlineConverter()),
        properties.getCadenceTaskList(),
        activities);
  }

  private static WorkerFactory startWorker(
      WorkflowClient client, String taskList, CadenceAgentActivities activities) {
    WorkerFactory factory = WorkerFactory.newInstance(client);
    Worker worker = factory.newWorker(taskList);
    worker.registerWorkflowImplementationTypes(CadenceMessageWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    factory.start();
    return factory;
  }

  private record TemporalTypeAdapter<T>(
      Class<T> valueType, Function<String, T> parseIso, LongFunction<T> fromMillis)
      implements JsonSerializer<T>, JsonDeserializer<T> {
    @Override
    public JsonElement serialize(T src, Type typeOfSrc, JsonSerializationContext context) {
      return src == null ? JsonNull.INSTANCE : new JsonPrimitive(src.toString());
    }

    @Override
    public T deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {
      if (json == null || json.isJsonNull()) {
        return null;
      }
      if (!json.isJsonPrimitive()) {
        throw new JsonParseException(
            "Unsupported " + valueType.getSimpleName() + " value: " + json);
      }
      JsonPrimitive primitive = json.getAsJsonPrimitive();
      if (primitive.isNumber()) {
        return fromMillis.apply(primitive.getAsLong());
      }
      String text = primitive.getAsString();
      if (text == null || text.isBlank()) {
        return null;
      }
      try {
        return parseIso.apply(text);
      } catch (DateTimeParseException ignored) {
        // Older workflow payloads can contain millisecond strings.
      }
      try {
        return fromMillis.apply(Long.parseLong(text));
      } catch (NumberFormatException ignored) {
        return null;
      }
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
        return LazyKt.lazyOf(null);
      }
      Object value = context.deserialize(json, Object.class);
      return LazyKt.lazyOf(value);
    }
  }
}

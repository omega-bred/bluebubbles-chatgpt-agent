package io.breland.bbagent.server.agent.cadence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.serviceclient.WorkflowServiceGrpc;
import io.breland.bbagent.server.agent.AgentWorkflowProperties;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import kotlin.Lazy;
import kotlin.LazyKt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CadenceLazyTypeAdapterTest {
  private final CadenceWorkflowConfig.LazyTypeAdapter adapter =
      new CadenceWorkflowConfig.LazyTypeAdapter();
  private final DataConverter converter =
      new CadenceWorkflowConfig()
          .cadenceWorkflowClient(mock(WorkflowServiceGrpc.class), new AgentWorkflowProperties())
          .getOptions()
          .getDataConverter();

  @ParameterizedTest
  @MethodSource("payloads")
  void decodedValuesAreInitializedAndKeepTheirWireFormat(String payload, String encoded) {
    Lazy<?> result =
        converter.fromData(payload.getBytes(StandardCharsets.UTF_8), Lazy.class, Lazy.class);

    assertThat(result.isInitialized()).isTrue();
    assertThat(result.getValue()).isSameAs(result.getValue());
    assertThat(converter.toData(result)).isEqualTo(encoded.getBytes(StandardCharsets.UTF_8));
  }

  private static Stream<Arguments> payloads() {
    return Stream.of(
        Arguments.of("\"value\"", "\"value\""),
        Arguments.of("\"\"", "\"\""),
        Arguments.of("\"雪 🌊\"", "\"雪 🌊\""),
        Arguments.of("true", "true"),
        Arguments.of("false", "false"),
        Arguments.of("0", "0.0"),
        Arguments.of("3.5", "3.5"),
        Arguments.of("[]", "[]"),
        Arguments.of("[1,\"value\",null]", "[1.0,\"value\",null]"),
        Arguments.of("{}", "{}"),
        Arguments.of("{\"label\":\"value\",\"count\":2}", "{\"label\":\"value\",\"count\":2.0}"),
        Arguments.of(
            "{\"nested\":[true,{\"value\":null}]}", "{\"nested\":[true,{\"value\":null}]}"));
  }

  @ParameterizedTest
  @MethodSource("nullInputs")
  void adapterNullInputsRemainInitializedWithoutDeserializing(JsonElement json) {
    var context = mock(JsonDeserializationContext.class);

    Lazy<?> result = adapter.deserialize(json, Lazy.class, context);

    assertThat(result.isInitialized()).isTrue();
    assertThat(result.getValue()).isNull();
    verifyNoInteractions(context);
  }

  private static Stream<JsonElement> nullInputs() {
    return Stream.of(null, JsonNull.INSTANCE);
  }

  @Test
  void deserializationRetainsTheExactContextValue() {
    var context = mock(JsonDeserializationContext.class);
    var json = new JsonPrimitive("value");
    var value = new Object();
    when(context.deserialize(json, Object.class)).thenReturn(value);

    Lazy<?> result = adapter.deserialize(json, Lazy.class, context);

    assertThat(result.isInitialized()).isTrue();
    assertThat(result.getValue()).isSameAs(value);
    verify(context).deserialize(json, Object.class);
  }

  @Test
  void converterStillReturnsNullForNullPayloads() {
    assertThat(converter.fromData(null, Lazy.class, Lazy.class)).isNull();
    assertThat(converter.fromData("null".getBytes(StandardCharsets.UTF_8), Lazy.class, Lazy.class))
        .isNull();
  }

  @Test
  void serializationDoesNotInitializeDeferredValues() {
    var context = mock(JsonSerializationContext.class);
    Lazy<?> deferred =
        LazyKt.lazy(
            () -> {
              throw new AssertionError("Deferred value must not be evaluated");
            });

    assertThat(adapter.serialize(deferred, Lazy.class, context)).isSameAs(JsonNull.INSTANCE);
    assertThat(deferred.isInitialized()).isFalse();
    assertThat(adapter.serialize(null, Lazy.class, context)).isSameAs(JsonNull.INSTANCE);
    verifyNoInteractions(context);
  }
}

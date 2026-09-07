package io.breland.bbagent.server.agent.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.breland.bbagent.server.agent.IncomingMessage;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MessageTransportRegistryTest {
  @Test
  void resolvesRegisteredTransportsAndPrefersBlueBubblesForFallback() {
    MessageTransport lxmf = transport(IncomingMessage.TRANSPORT_LXMF);
    MessageTransport blueBubbles = transport(IncomingMessage.TRANSPORT_BLUEBUBBLES);
    MessageTransportRegistry registry = new MessageTransportRegistry(List.of(lxmf, blueBubbles));

    assertThat(registry.resolve(message(IncomingMessage.TRANSPORT_LXMF))).isSameAs(lxmf);
    assertThat(registry.resolve(message(IncomingMessage.TRANSPORT_BLUEBUBBLES)))
        .isSameAs(blueBubbles);
    assertThat(registry.resolve(message("unknown"))).isSameAs(blueBubbles);
    assertThat(registry.resolve(null)).isSameAs(blueBubbles);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void defaultsMissingMessageTransportToBlueBubbles(String id) {
    MessageTransport blueBubbles = transport(IncomingMessage.TRANSPORT_BLUEBUBBLES);
    MessageTransportRegistry registry = new MessageTransportRegistry(List.of(blueBubbles));

    assertThat(registry.resolve(message(id))).isSameAs(blueBubbles);
  }

  @Test
  void skipsInvalidEntriesAndUsesFirstRegisteredTransportWithoutBlueBubbles() {
    MessageTransport lxmf = transport(IncomingMessage.TRANSPORT_LXMF);
    MessageTransportRegistry registry =
        new MessageTransportRegistry(
            Arrays.asList(
                null, transport(null), transport(""), transport(" "), lxmf, transport("other")));

    assertThat(registry.resolve(message("unknown"))).isSameAs(lxmf);
    assertThat(registry.resolve(message(null))).isSameAs(lxmf);
    assertThat(registry.resolve(null)).isSameAs(lxmf);
  }

  @ParameterizedTest
  @ValueSource(strings = {IncomingMessage.TRANSPORT_BLUEBUBBLES, IncomingMessage.TRANSPORT_LXMF})
  void lastDuplicateWinsWithoutChangingFallbackOrder(String id) {
    MessageTransport replacement = transport(id);
    MessageTransportRegistry registry =
        new MessageTransportRegistry(List.of(transport(id), transport("other"), replacement));

    assertThat(registry.resolve(message(id))).isSameAs(replacement);
    assertThat(registry.resolve(message("unknown"))).isSameAs(replacement);
    assertThat(registry.resolve(null)).isSameAs(replacement);
  }

  @Test
  void returnsNullWhenNoValidTransportsAreRegistered() {
    for (List<MessageTransport> transports :
        List.of(
            List.<MessageTransport>of(), Arrays.asList(null, transport(null), transport(" ")))) {
      MessageTransportRegistry registry = new MessageTransportRegistry(transports);

      assertThat(registry.resolve(message(IncomingMessage.TRANSPORT_BLUEBUBBLES))).isNull();
      assertThat(registry.resolve(message("unknown"))).isNull();
      assertThat(registry.resolve(null)).isNull();
    }
  }

  private static MessageTransport transport(String id) {
    MessageTransport transport = mock(MessageTransport.class);
    when(transport.id()).thenReturn(id);
    return transport;
  }

  private static IncomingMessage message(String transport) {
    return new IncomingMessage(
        transport, "chat", "message", null, "text", false, null, "sender", false, null, List.of(),
        false);
  }
}

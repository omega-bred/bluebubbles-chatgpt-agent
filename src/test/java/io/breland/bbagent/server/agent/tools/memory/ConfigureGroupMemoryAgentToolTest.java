package io.breland.bbagent.server.agent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemorySettingsService;
import io.breland.bbagent.server.agent.memory.ConversationMemorySettingsService.GroupMemorySetting;
import io.breland.bbagent.server.agent.memory.ConversationMemorySettingsService.GroupMemoryUpdateResult;
import io.breland.bbagent.server.agent.profile.AgentProfile;
import io.breland.bbagent.server.agent.tools.ToolContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConfigureGroupMemoryAgentToolTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void schemaExposesOnlyEnabledAndToolDerivesCurrentContext() throws Exception {
    ConversationMemorySettingsService settingsService =
        Mockito.mock(ConversationMemorySettingsService.class);
    IncomingMessage message = groupMessage();
    when(settingsService.tryUpdateGroupMemory("account-1", message.chatGuid(), true))
        .thenReturn(
            new GroupMemoryUpdateResult(
                true,
                "Group memory enabled.",
                new GroupMemorySetting(
                    true, true, "Memory", "Prospective group memory", Instant.now())));
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    when(agent.getObjectMapper()).thenReturn(mapper);
    AgentProfile profile = Mockito.mock(AgentProfile.class);
    when(profile.resolveOrCreateAccountId(message)).thenReturn(Optional.of("account-1"));
    ToolContext context = new ToolContext(agent, profile, message, null);
    var tool = new ConfigureGroupMemoryAgentTool(settingsService).getTool();

    String output = tool.handler().apply(context, mapper.readTree("{\"enabled\":true}"));

    String schema = String.valueOf(tool.parameters()._additionalProperties());
    assertThat(schema).contains("enabled");
    assertThat(schema).doesNotContain("chatGuid", "chat_guid", "account_id", "conversation_id");
    assertThat(output).containsIgnoringCase("enabled");
    verify(settingsService).tryUpdateGroupMemory("account-1", message.chatGuid(), true);
  }

  @Test
  void directConversationIsRejectedWithoutCallingSettingsService() throws Exception {
    ConversationMemorySettingsService settingsService =
        Mockito.mock(ConversationMemorySettingsService.class);
    IncomingMessage direct =
        new IncomingMessage(
            IncomingMessage.TRANSPORT_BLUEBUBBLES,
            "iMessage;-;direct",
            "message-1",
            null,
            "enable memory",
            false,
            BBMessageAgent.IMESSAGE_SERVICE,
            "person@example.com",
            false,
            Instant.now(),
            List.of(),
            false);
    BBMessageAgent agent = Mockito.mock(BBMessageAgent.class);
    when(agent.getObjectMapper()).thenReturn(mapper);

    String output =
        new ConfigureGroupMemoryAgentTool(settingsService)
            .getTool()
            .handler()
            .apply(new ToolContext(agent, direct, null), mapper.readTree("{\"enabled\":true}"));

    assertThat(output).containsIgnoringCase("group");
    Mockito.verifyNoInteractions(settingsService);
  }

  private IncomingMessage groupMessage() {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        "iMessage;+;group",
        "message-1",
        null,
        "enable memory",
        false,
        BBMessageAgent.IMESSAGE_SERVICE,
        "person@example.com",
        true,
        Instant.now(),
        List.of(),
        false);
  }
}

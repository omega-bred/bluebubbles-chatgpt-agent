package io.breland.bbagent.server.agent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupPreferenceSetting;
import io.breland.bbagent.server.agent.memory.ConversationMemoryModels.CatchupPreferenceUpdate;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.memory.ProactiveCatchupService;
import io.breland.bbagent.server.agent.profile.AgentProfile;
import io.breland.bbagent.server.agent.tools.ToolContextFixture;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConfigureGroupCatchupAgentToolTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void enablesPersonalCatchupsFromDirectChatWithoutExposingIds() throws Exception {
    ProactiveCatchupService service = Mockito.mock(ProactiveCatchupService.class);
    MemoryScopeResolver resolver = Mockito.mock(MemoryScopeResolver.class);
    when(resolver.proactiveCatchupService()).thenReturn(Optional.of(service));
    when(service.updateForGroup(
            "account-1", "Project", true, "America/Los_Angeles", "22:00", "08:00"))
        .thenReturn(
            new CatchupPreferenceUpdate(
                new CatchupPreferenceSetting(
                    true,
                    true,
                    "America/Los_Angeles",
                    "22:00",
                    "08:00",
                    Instant.parse("2026-08-09T15:00:00Z"),
                    "Project"),
                List.of()));
    AgentProfile profile = Mockito.mock(AgentProfile.class);
    IncomingMessage message = message(false);
    when(profile.resolveCanonicalAccountId(message)).thenReturn(Optional.of("account-1"));
    String output =
        new ConfigureGroupCatchupAgentTool(resolver)
            .getTool()
            .handler()
            .apply(
                ToolContextFixture.with(message).objectMapper(mapper).profile(profile).build(),
                mapper.readTree(
                    """
                    {"group":"Project","enabled":true,"timezone":"America/Los_Angeles",
                     "quiet_start":"22:00","quiet_end":"08:00"}
                    """));

    assertThat(output)
        .contains("developments since your last catch-up", "America/Los_Angeles", "22:00", "08:00")
        .doesNotContain("account-1", "conversation_id", "chatGuid");
  }

  @Test
  void returnsSafeDisambiguationOptions() throws Exception {
    ProactiveCatchupService service = Mockito.mock(ProactiveCatchupService.class);
    MemoryScopeResolver resolver = Mockito.mock(MemoryScopeResolver.class);
    when(resolver.proactiveCatchupService()).thenReturn(Optional.of(service));
    when(service.updateForGroup("account-1", "Project", true, null, null, null))
        .thenReturn(
            new CatchupPreferenceUpdate(
                new CatchupPreferenceSetting(false, false, "UTC", "22:00", "08:00", null, null),
                List.of(
                    "Project (last active 2026-08-08T20:00:00Z)",
                    "Project (last active 2026-08-07T20:00:00Z)")));
    AgentProfile profile = Mockito.mock(AgentProfile.class);
    IncomingMessage message = message(false);
    when(profile.resolveCanonicalAccountId(message)).thenReturn(Optional.of("account-1"));
    String output =
        new ConfigureGroupCatchupAgentTool(resolver)
            .getTool()
            .handler()
            .apply(
                ToolContextFixture.with(message).objectMapper(mapper).profile(profile).build(),
                mapper.readTree("{\"group\":\"Project\",\"enabled\":true}"));

    assertThat(output).contains("disambiguation_required", "group_options");
  }

  @Test
  void rejectsGroupChatInvocation() {
    String output =
        new ConfigureGroupCatchupAgentTool(Mockito.mock(MemoryScopeResolver.class))
            .getTool()
            .handler()
            .apply(
                ToolContextFixture.with(message(true)).objectMapper(mapper).build(),
                mapper.createObjectNode());

    assertThat(output).contains("one-to-one");
  }

  private IncomingMessage message(boolean group) {
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_BLUEBUBBLES,
        group ? "iMessage;+;group" : "iMessage;-;person",
        "message-1",
        null,
        "hello",
        false,
        BBMessageAgent.IMESSAGE_SERVICE,
        "+15555550123",
        group,
        Instant.parse("2026-08-08T20:00:00Z"),
        List.of(),
        false);
  }
}

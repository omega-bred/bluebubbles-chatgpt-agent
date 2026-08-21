package io.breland.bbagent.server.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.OpenAIClient;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.memory.MemoryScopeResolver;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.kubernetes.KubernetesPodLogsAgentTool;
import io.breland.bbagent.server.agent.tools.kubernetes.KubernetesReadOnlyAgentTool;
import io.breland.bbagent.server.agent.tools.memory.ConfigureGroupCatchupAgentTool;
import io.breland.bbagent.server.agent.tools.memory.GetGroupCatchupAgentTool;
import io.breland.bbagent.server.agent.tools.memory.Mem0Client;
import io.breland.bbagent.server.agent.tools.search.ToolSearchAgentTool;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AgentToolRegistryTest {
  private static final String KUBERNETES_TOOL_ALLOWED_ACCOUNT_ID =
      "9f80c2a0-de6f-4c56-8027-29b1673bb0d5";
  private static final String LEGACY_ALLOWED_SENDER = "+18033861737";

  @Test
  void includesKubernetesToolsForAllowedAccountId() {
    AgentToolRegistry registry = registryForAccount(KUBERNETES_TOOL_ALLOWED_ACCOUNT_ID);

    Set<String> tools = toolNames(registry.availableTools(directMessage("someone-else")));

    assertTrue(tools.contains(KubernetesReadOnlyAgentTool.TOOL_NAME));
    assertTrue(tools.contains(KubernetesPodLogsAgentTool.TOOL_NAME));
    assertNotNull(
        registry
            .resolveTool(KubernetesPodLogsAgentTool.TOOL_NAME, directMessage("someone-else"))
            .tool());
  }

  @Test
  void legacyAllowedSenderDoesNotExposeKubernetesToolsForDifferentAccount() {
    AgentToolRegistry registry = registryForAccount("different-account");

    Set<String> tools = toolNames(registry.availableTools(directMessage(LEGACY_ALLOWED_SENDER)));

    assertFalse(tools.contains(KubernetesReadOnlyAgentTool.TOOL_NAME));
    assertFalse(tools.contains(KubernetesPodLogsAgentTool.TOOL_NAME));
    assertNull(
        registry
            .resolveTool(KubernetesPodLogsAgentTool.TOOL_NAME, directMessage(LEGACY_ALLOWED_SENDER))
            .tool());
  }

  @Test
  void groupMessagesDoNotExposeKubernetesToolsEvenForAllowedAccount() {
    AgentToolRegistry registry = registryForAccount(KUBERNETES_TOOL_ALLOWED_ACCOUNT_ID);

    Set<String> tools = toolNames(registry.availableTools(groupMessage()));

    assertFalse(tools.contains(KubernetesReadOnlyAgentTool.TOOL_NAME));
    assertFalse(tools.contains(KubernetesPodLogsAgentTool.TOOL_NAME));
  }

  @Test
  void toolSearchOnlyReturnsToolsAllowedForCurrentMessage() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    AgentToolRegistry allowedRegistry = registryForAccount(KUBERNETES_TOOL_ALLOWED_ACCOUNT_ID);
    AgentToolRegistry deniedRegistry = registryForAccount("different-account");
    ObjectNode args = mapper.createObjectNode();
    args.put("query", KubernetesPodLogsAgentTool.TOOL_NAME);
    args.put("maxResults", 10);

    List<String> allowedNames =
        toolSearch(
            allowedRegistry,
            mapper,
            new ToolContext(mock(BBMessageAgent.class), directMessage("someone-else"), null),
            args);
    List<String> deniedNames =
        toolSearch(
            deniedRegistry,
            mapper,
            new ToolContext(mock(BBMessageAgent.class), directMessage("someone-else"), null),
            args);

    assertTrue(allowedNames.contains(KubernetesPodLogsAgentTool.TOOL_NAME));
    assertFalse(deniedNames.contains(KubernetesPodLogsAgentTool.TOOL_NAME));
  }

  @Test
  void toolSearchFindsRepresentativeToolsFromNaturalLanguageQueries() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    AgentToolRegistry registry = registryForAccount("different-account");
    ToolContext context =
        new ToolContext(mock(BBMessageAgent.class), directMessage("someone-else"), null);
    Map<String, String> expectedToolByQuery =
        Map.of(
            "send a plain text message", "send_text",
            "create an event in google calendar", "create_event",
            "remember a user preference", "memory_save",
            "schedule a reminder for later", "schedule_event",
            "change the assistant name", "assistant_name_tool");

    for (Map.Entry<String, String> entry : expectedToolByQuery.entrySet()) {
      ObjectNode args = mapper.createObjectNode();
      args.put("query", entry.getKey());
      args.put("maxResults", 5);

      List<String> matches = toolSearch(registry, mapper, context, args);

      assertTrue(
          matches.contains(entry.getValue()),
          () -> entry.getKey() + " should find " + entry.getValue() + " but got " + matches);
    }
  }

  @Test
  void groupCatchupIsAvailableInDirectAndGroupChatsWhileConfigurationRemainsDirectOnly() {
    AgentToolRegistry registry = registryForAccount("account-1", mock(MemoryScopeResolver.class));

    assertTrue(
        toolNames(registry.availableTools(directMessage("person")))
            .contains(GetGroupCatchupAgentTool.TOOL_NAME));
    assertTrue(
        toolNames(registry.availableTools(groupMessage()))
            .contains(GetGroupCatchupAgentTool.TOOL_NAME));
    assertNotNull(registry.resolveTool(GetGroupCatchupAgentTool.TOOL_NAME, groupMessage()).tool());
    assertTrue(
        toolNames(registry.availableTools(directMessage("person")))
            .contains(ConfigureGroupCatchupAgentTool.TOOL_NAME));
    assertFalse(
        toolNames(registry.availableTools(groupMessage()))
            .contains(ConfigureGroupCatchupAgentTool.TOOL_NAME));
    assertNull(
        registry.resolveTool(ConfigureGroupCatchupAgentTool.TOOL_NAME, groupMessage()).tool());
  }

  private static AgentToolRegistry registryForAccount(String accountId) {
    return registryForAccount(accountId, null);
  }

  private static AgentToolRegistry registryForAccount(
      String accountId, MemoryScopeResolver memoryScopeResolver) {
    BBHttpClientWrapper bbHttpClientWrapper = mock(BBHttpClientWrapper.class);
    AgentProfileService profileService = mock(AgentProfileService.class);
    when(profileService.resolveOrCreateAccountId(any())).thenReturn(Optional.ofNullable(accountId));
    return new AgentToolRegistry(
        bbHttpClientWrapper,
        mock(Mem0Client.class),
        mock(GcalClient.class),
        null,
        mock(GiphyClient.class),
        MessageTransportRegistry.blueBubblesOnly(bbHttpClientWrapper),
        new ObjectMapper(),
        () -> mock(OpenAIClient.class),
        null,
        null,
        mock(CadenceWorkflowLauncher.class),
        profileService,
        null,
        null,
        null,
        memoryScopeResolver);
  }

  private static Set<String> toolNames(List<AgentTool> tools) {
    return tools.stream().map(AgentTool::name).collect(Collectors.toSet());
  }

  private static List<String> toolSearch(
      AgentToolRegistry registry, ObjectMapper mapper, ToolContext context, ObjectNode args)
      throws Exception {
    AgentTool tool = registry.resolveTool(ToolSearchAgentTool.TOOL_NAME, context.message()).tool();
    assertNotNull(tool);
    String output = tool.handler().apply(context, args);
    return mapper.readValue(output, new TypeReference<List<String>>() {});
  }

  private static IncomingMessage directMessage(String sender) {
    return message(sender, false);
  }

  private static IncomingMessage groupMessage() {
    return message("group-member", true);
  }

  private static IncomingMessage message(String sender, boolean isGroup) {
    return new IncomingMessage(
        "iMessage;+;chat",
        "msg-1",
        null,
        "hello",
        false,
        "iMessage",
        sender,
        isGroup,
        Instant.EPOCH,
        List.of(),
        false);
  }
}

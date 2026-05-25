package io.breland.bbagent.server.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.kubernetes.KubernetesPodLogsAgentTool;
import io.breland.bbagent.server.agent.tools.kubernetes.KubernetesReadOnlyAgentTool;
import io.breland.bbagent.server.agent.tools.memory.Mem0Client;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Instant;
import java.util.List;
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

  private static AgentToolRegistry registryForAccount(String accountId) {
    BBHttpClientWrapper bbHttpClientWrapper = mock(BBHttpClientWrapper.class);
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
        message -> Optional.ofNullable(accountId));
  }

  private static Set<String> toolNames(List<AgentTool> tools) {
    return tools.stream().map(AgentTool::name).collect(Collectors.toSet());
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

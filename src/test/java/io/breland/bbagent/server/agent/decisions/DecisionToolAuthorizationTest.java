package io.breland.bbagent.server.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import io.breland.bbagent.server.agent.tools.AgentTool;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecisionToolAuthorizationTest {
  @Test
  void preloadSuggestionsStillRequireCurrentTransportAndAccountPermissions() {
    var registry = DecisionFixtures.toolRegistry("lxmf");
    var exposed =
        registry.toolsForModel(
            MessageDecisionServiceTest.message("What is my quota?"),
            List.of(),
            List.of(
                "get_usage_limits", "send_reaction", "kubernetes_read_only_query", "unknown_tool"));
    assertThat(exposed)
        .extracting(AgentTool::name)
        .contains("toolSearchTool", "get_usage_limits")
        .doesNotContain("send_reaction", "kubernetes_read_only_query", "unknown_tool");
  }
}

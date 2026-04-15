package io.breland.bbagent.server.agent.tools.coder;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoderToolNameMapperTest {

  @Test
  void normalizesAndBoundsToolNames() {
    CoderToolNameMapper mapper = new CoderToolNameMapper();

    String name = mapper.toAgentToolName("coder create task with spaces and symbols!");

    assertTrue(name.startsWith(CoderMcpClient.TOOL_PREFIX));
    assertTrue(name.length() <= 64);
    assertTrue(name.matches("[A-Za-z0-9_-]+"));
  }

  @Test
  void disambiguatesDuplicateNames() {
    CoderToolNameMapper mapper = new CoderToolNameMapper();

    String first = mapper.toAgentToolName("same");
    String second = mapper.disambiguateAgentToolName("same", 2);

    assertNotEquals(first, second);
    assertTrue(second.endsWith("_2"));
  }
}

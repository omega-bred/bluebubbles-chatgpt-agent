package io.breland.bbagent.server.agent.tools.scheduled;

import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.tools.memory.Mem0Client;

public class ScheduledEventTool implements ToolProvider {
    public static final String TOOL_NAME = "memory_update";
    private final CadenceWorkflowLauncher cadenceWorkflowLauncher;
    public ScheduledEventTool(CadenceWorkflowLauncher cadenceWorkflowLauncher) {
        this.cadenceWorkflowLauncher = cadenceWorkflowLauncher;
    }
}

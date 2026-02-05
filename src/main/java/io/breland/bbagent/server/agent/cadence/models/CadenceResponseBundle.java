package io.breland.bbagent.server.agent.cadence.models;

import java.util.List;

public record CadenceResponseBundle(
    String responseJson,
    String assistantText,
    String toolContextItemsJson,
    List<CadenceToolCall> toolCalls) {}

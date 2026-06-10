package io.breland.bbagent.server.agent.tools.search;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.ToolJson;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.apache.commons.lang3.StringUtils;
import org.springaicommunity.tool.search.ToolReference;
import org.springaicommunity.tool.search.ToolSearchRequest;
import org.springaicommunity.tool.searcher.LuceneToolSearcher;

public final class ToolSearchAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "toolSearchTool";
  private static final int DEFAULT_MAX_RESULTS = 5;
  private static final int MIN_MAX_RESULTS = 1;
  private static final int MAX_MAX_RESULTS = 10;
  private static final String CATEGORIES =
      "bluebubbles, google_calendar, website, assistant, scheduled, memory, kubernetes, feedback,"
          + " limits, other";

  private final ObjectMapper objectMapper;
  private final BiFunction<IncomingMessage, String, List<ToolIndexEntry>> toolEntriesProvider;

  public ToolSearchAgentTool(
      ObjectMapper objectMapper,
      BiFunction<IncomingMessage, String, List<ToolIndexEntry>> toolEntriesProvider) {
    this.objectMapper = objectMapper;
    this.toolEntriesProvider = toolEntriesProvider;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        """
        Search for tools in the tool registry to discover capabilities for completing the current task.
        Use this when you need functionality not provided by your currently available tools.
        The search checks tool names, descriptions, categories, and parameter information to find
        relevant tools. It returns tool names; those tools will be expanded into full definitions on
        the next model call so you can invoke them normally.
        """,
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "query",
                    Map.of(
                        "type",
                        "string",
                        "description",
                        "A natural language search query describing the tool capability you need."),
                    "maxResults",
                    Map.of(
                        "type",
                        "integer",
                        "minimum",
                        MIN_MAX_RESULTS,
                        "maximum",
                        MAX_MAX_RESULTS,
                        "description",
                        "Maximum number of tool references to return. Default is 5."),
                    "categoryFilter",
                    Map.of(
                        "type",
                        "string",
                        "description",
                        "Optional category filter. Known categories: " + CATEGORIES + ".")),
                "required",
                List.of("query"),
                "additionalProperties",
                false)),
        false,
        this::search);
  }

  private String search(ToolContext context, JsonNode args) {
    String query = StringUtils.trimToEmpty(textArg(args, "query"));
    if (query.isBlank()) {
      return "[]";
    }
    int maxResults = intArg(args, "maxResults", DEFAULT_MAX_RESULTS);
    maxResults = Math.max(MIN_MAX_RESULTS, Math.min(MAX_MAX_RESULTS, maxResults));
    String categoryFilter = StringUtils.trimToNull(textArg(args, "categoryFilter"));
    List<ToolIndexEntry> toolEntries =
        toolEntriesProvider.apply(context == null ? null : context.message(), categoryFilter);
    String sessionId = "agent-tool-search-" + UUID.randomUUID();
    try (LuceneToolSearcher searcher = new LuceneToolSearcher(0.2f)) {
      for (ToolIndexEntry entry : toolEntries) {
        searcher.indexTool(
            sessionId,
            ToolReference.builder().toolName(entry.toolName()).summary(entry.summary()).build());
      }
      List<String> toolNames =
          searcher
              .search(new ToolSearchRequest(sessionId, query, maxResults, categoryFilter))
              .toolReferences()
              .stream()
              .map(ToolReference::toolName)
              .distinct()
              .toList();
      return objectMapper.writeValueAsString(toolNames);
    } catch (IOException e) {
      return ToolJson.stringify(objectMapper, List.of(), "[]");
    }
  }

  private static String textArg(JsonNode args, String fieldName) {
    if (args == null) {
      return null;
    }
    JsonNode value = args.get(fieldName);
    if (value == null || value.isNull()) {
      return null;
    }
    return value.asText(null);
  }

  private static int intArg(JsonNode args, String fieldName, int fallback) {
    if (args == null) {
      return fallback;
    }
    JsonNode value = args.get(fieldName);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (value.canConvertToInt()) {
      return value.asInt(fallback);
    }
    if (value.isTextual()) {
      try {
        return Integer.parseInt(value.asText());
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  public static String summaryFor(AgentTool tool, String category, ObjectMapper objectMapper) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("name", tool.name());
    summary.put("category", category);
    summary.put("description", tool.description());
    summary.put("parameters", ToolJson.stringify(objectMapper, tool.parameters(), ""));
    return ToolJson.stringify(
        objectMapper,
        summary,
        tool.name() + " " + category + " " + StringUtils.defaultString(tool.description()));
  }

  public record ToolIndexEntry(String toolName, String summary) {}
}

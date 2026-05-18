package io.breland.bbagent.server.agent.tools.coder;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.persistence.coder.CoderAsyncTaskStartEntity;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventTool;
import io.breland.bbagent.server.agent.workflowcallback.WorkflowCallbackService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class StartCoderAsyncTaskAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "start_coder_async_task";
  public static final String CREATE_TASK_MCP_TOOL = "coder_create_task";
  public static final String LIST_TEMPLATES_MCP_TOOL = "coder_list_templates";

  private static final long DEFAULT_FALLBACK_DELAY_SECONDS = 300L;
  private static final long DEFAULT_CALLBACK_EXPIRES_IN_SECONDS = 24L * 60L * 60L;
  private static final int MAX_INCLUDED_RESULT_CHARS = 2400;

  private final CoderMcpClient coderMcpClient;
  private final WorkflowCallbackService callbackService;
  private final CoderAsyncTaskStartStore taskStartStore;
  private final @Nullable CadenceWorkflowLauncher cadenceWorkflowLauncher;

  @Schema(description = "Start a long-running Coder AI task with callback and fallback watching.")
  public record StartCoderAsyncTaskRequest(
      @Schema(
              description = "The complete task prompt to send to the Coder AI task.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String task,
      @JsonProperty("template_version_id")
          @Schema(
              description =
                  "Optional exact Coder template version UUID. If omitted, the tool selects the"
                      + " task template.")
          String templateVersionId,
      @JsonProperty("template_name")
          @Schema(
              description =
                  "Optional template name/display-name hint. Defaults to the best AI task"
                      + " template.")
          String templateName,
      @JsonProperty("fallback_delay_seconds")
          @Schema(description = "Optional delay before the fallback check. Defaults to 5 minutes.")
          Long fallbackDelaySeconds,
      @JsonProperty("callback_expires_in_seconds")
          @Schema(description = "Optional callback lifetime. Defaults to 24 hours.")
          Long callbackExpiresInSeconds) {}

  public StartCoderAsyncTaskAgentTool(
      CoderMcpClient coderMcpClient,
      WorkflowCallbackService callbackService,
      CoderAsyncTaskStartStore taskStartStore,
      @Nullable CadenceWorkflowLauncher cadenceWorkflowLauncher) {
    this.coderMcpClient = coderMcpClient;
    this.callbackService = callbackService;
    this.taskStartStore = taskStartStore;
    this.cadenceWorkflowLauncher = cadenceWorkflowLauncher;
  }

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Start a Coder AI task end-to-end. Use this when the user asks to start, run, kick off, or"
            + " watch a Coder AI/dev task. This tool creates the webhook callback, injects callback"
            + " instructions into the Coder task prompt, starts the Coder task, and schedules a"
            + " fallback status check. Do not call create_workflow_callback or coder_create_task"
            + " separately for Coder AI tasks.",
        jsonSchema(StartCoderAsyncTaskRequest.class),
        false,
        (context, args) -> {
          StartCoderAsyncTaskRequest request =
              context.getMapper().convertValue(args, StartCoderAsyncTaskRequest.class);
          return startTask(context, request);
        });
  }

  private String startTask(ToolContext context, StartCoderAsyncTaskRequest request) {
    ObjectMapper mapper = context.getMapper();
    Map<String, Object> response = new LinkedHashMap<>();
    String idempotencyKey = null;
    boolean reservedStart = false;
    try {
      if (request == null || StringUtils.isBlank(request.task())) {
        return "missing task";
      }
      if (!coderMcpClient.isConfigured()) {
        return "Coder MCP is not configured";
      }
      String accountId = context.accountId();
      if (StringUtils.isBlank(accountId)) {
        return "missing Coder account identity";
      }
      if (!coderMcpClient.isLinked(accountId)) {
        response.put("started", false);
        response.put("linked", false);
        coderMcpClient
            .getAuthUrl(
                accountId,
                context.message() == null ? null : context.message().chatGuid(),
                context.message() == null ? null : context.message().messageGuid())
            .ifPresent(authUrl -> response.put("auth_url", authUrl));
        response.put("message", "Coder is not linked. Ask the user to complete the auth_url.");
        return mapper.writeValueAsString(response);
      }

      idempotencyKey = idempotencyKey(context, accountId, request);
      CoderAsyncTaskStartStore.Reservation reservation =
          taskStartStore.reserve(
              idempotencyKey,
              accountId,
              context.message() == null
                  ? ""
                  : StringUtils.defaultIfBlank(context.message().chatGuid(), ""),
              context.message() == null ? null : context.message().messageGuid(),
              sha256(normalizeTask(request.task())),
              request.task().trim());
      if (!reservation.shouldStart()) {
        return replayExistingStart(reservation.entity(), mapper);
      }
      reservedStart = true;

      TemplateSelection template =
          selectTemplate(accountId, request.templateVersionId(), request.templateName(), mapper);
      Duration callbackTtl =
          Duration.ofSeconds(
              positiveOrDefault(
                  request.callbackExpiresInSeconds(), DEFAULT_CALLBACK_EXPIRES_IN_SECONDS));
      WorkflowCallbackService.CreatedCallback callback =
          callbackService.createCallback(
              context.message(),
              "Coder async task: " + summarize(request.task()),
              "Review the Coder task result/logs and notify the user with the final answer. "
                  + "Original task: "
                  + request.task().trim(),
              callbackTtl);

      String taskPrompt = buildTaskPrompt(request.task(), callback);
      Map<String, Object> createTaskArgs = new LinkedHashMap<>();
      createTaskArgs.put("input", taskPrompt);
      createTaskArgs.put("template_version_id", template.templateVersionId());
      String coderResult =
          coderMcpClient.callMcpTool(accountId, CREATE_TASK_MCP_TOOL, createTaskArgs);

      response.put("started", !isCoderError(coderResult, mapper));
      response.put("template_version_id", template.templateVersionId());
      response.put("template_name", template.displayName());
      response.put("callback_id", callback.callbackId());
      response.put("callback_expires_at", callback.expiresAt().toString());
      response.put("coder_result", mapper.readTree(coderResult));
      if (Boolean.TRUE.equals(response.get("started"))) {
        response.put(
            "fallback_check", scheduleFallback(context, request, callback, coderResult, mapper));
      } else {
        response.put(
            "fallback_check", "not scheduled because Coder task creation returned an error");
      }
      response.put(
          "message",
          "Coder task start flow completed. Do not reveal callback secrets; summarize status to the"
              + " user.");
      String responseJson = mapper.writeValueAsString(response);
      taskStartStore.markStarted(idempotencyKey, responseJson);
      return responseJson;
    } catch (Exception e) {
      log.warn("Failed to start Coder async task", e);
      if (idempotencyKey != null && reservedStart) {
        taskStartStore.markFailed(idempotencyKey, e.getMessage());
      }
      return "failed to start Coder async task: " + e.getMessage();
    }
  }

  private String replayExistingStart(CoderAsyncTaskStartEntity entity, ObjectMapper mapper)
      throws Exception {
    Map<String, Object> response = new LinkedHashMap<>();
    if (CoderAsyncTaskStartStore.STATUS_STARTED.equals(entity.getStatus())
        && StringUtils.isNotBlank(entity.getResponseJson())) {
      JsonNode original = mapper.readTree(entity.getResponseJson());
      ObjectNode replay =
          original.isObject() ? (ObjectNode) original.deepCopy() : mapper.createObjectNode();
      replay.put("deduplicated", true);
      replay.put("start_status", entity.getStatus());
      replay.put(
          "message",
          "This Coder task was already started for the current iMessage. Do not call"
              + " start_coder_async_task again; summarize the existing status to the user.");
      return mapper.writeValueAsString(replay);
    }
    response.put("deduplicated", true);
    response.put("start_status", entity.getStatus());
    response.put("started", CoderAsyncTaskStartStore.STATUS_STARTING.equals(entity.getStatus()));
    if (CoderAsyncTaskStartStore.STATUS_FAILED.equals(entity.getStatus())) {
      response.put(
          "error", StringUtils.defaultIfBlank(entity.getErrorMessage(), "previous start failed"));
      response.put(
          "message",
          "This iMessage already attempted to start a Coder task and failed. Do not call"
              + " start_coder_async_task again unless the user sends a new retry request.");
    } else {
      response.put(
          "message",
          "This Coder task is already being started for the current iMessage. Do not call"
              + " start_coder_async_task again; tell the user startup is in progress.");
    }
    return mapper.writeValueAsString(response);
  }

  private TemplateSelection selectTemplate(
      String accountId,
      @Nullable String templateVersionId,
      @Nullable String templateName,
      ObjectMapper mapper)
      throws Exception {
    if (StringUtils.isNotBlank(templateVersionId)) {
      return new TemplateSelection(
          templateVersionId.trim(), StringUtils.defaultIfBlank(templateName, "provided"));
    }
    String listResult = coderMcpClient.callMcpTool(accountId, LIST_TEMPLATES_MCP_TOOL, Map.of());
    List<JsonNode> templates = extractTemplateNodes(listResult, mapper);
    if (templates.isEmpty()) {
      throw new IllegalStateException("No Coder templates were returned");
    }
    JsonNode selected = null;
    int selectedScore = Integer.MIN_VALUE;
    for (JsonNode template : templates) {
      String activeVersionId = text(template, "active_version_id", "activeVersionId");
      if (StringUtils.isBlank(activeVersionId)) {
        continue;
      }
      int score = scoreTemplate(template, templateName);
      if (score > selectedScore) {
        selected = template;
        selectedScore = score;
      }
    }
    if (selected == null
        || (StringUtils.isNotBlank(templateName) && selectedScore <= 0)
        || (StringUtils.isBlank(templateName) && selectedScore <= 0 && templates.size() > 1)) {
      throw new IllegalStateException(
          "Could not find a Coder task template. Available templates: "
              + templateSummaries(templates));
    }
    return new TemplateSelection(
        text(selected, "active_version_id", "activeVersionId"),
        StringUtils.defaultIfBlank(
            text(selected, "display_name", "displayName"), text(selected, "name")));
  }

  private List<JsonNode> extractTemplateNodes(String listResult, ObjectMapper mapper)
      throws Exception {
    JsonNode root = mapper.readTree(listResult);
    List<JsonNode> templates = new ArrayList<>();
    appendTemplates(templates, root.path("structured_content"));
    if (root.path("content").isArray()) {
      for (JsonNode content : root.path("content")) {
        String text = content.path("text").asText(null);
        if (StringUtils.isNotBlank(text)) {
          appendTemplates(templates, mapper.readTree(text));
        }
      }
    }
    appendTemplates(templates, root);
    return templates;
  }

  private void appendTemplates(List<JsonNode> templates, JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return;
    }
    if (node.isArray()) {
      node.forEach(templates::add);
      return;
    }
    if (node.has("templates") && node.get("templates").isArray()) {
      node.get("templates").forEach(templates::add);
      return;
    }
    if (node.has("active_version_id") || node.has("activeVersionId")) {
      templates.add(node);
    }
  }

  private int scoreTemplate(JsonNode template, @Nullable String preferredName) {
    String name = StringUtils.lowerCase(text(template, "name"), Locale.ROOT);
    String displayName =
        StringUtils.lowerCase(text(template, "display_name", "displayName"), Locale.ROOT);
    String description = StringUtils.lowerCase(text(template, "description"), Locale.ROOT);
    String id = StringUtils.lowerCase(text(template, "id"), Locale.ROOT);
    String activeVersionId =
        StringUtils.lowerCase(text(template, "active_version_id", "activeVersionId"), Locale.ROOT);
    String haystack = String.join(" ", name, displayName, description, id, activeVersionId);
    if (StringUtils.isNotBlank(preferredName)) {
      String preferred = StringUtils.lowerCase(preferredName, Locale.ROOT);
      if (name.equals(preferred)
          || displayName.equals(preferred)
          || id.equals(preferred)
          || activeVersionId.equals(preferred)) {
        return 1000;
      }
      return haystack.contains(preferred) ? 500 : 0;
    }
    int score = 0;
    if ("tasks".equals(name)) {
      score += 120;
    }
    if (displayName.contains("task") || name.contains("task")) {
      score += 100;
    }
    if (description.contains("ai task")) {
      score += 80;
    }
    if (haystack.contains("opencode")) {
      score += 60;
    }
    if (haystack.contains("ai")) {
      score += 20;
    }
    return score;
  }

  private String buildTaskPrompt(
      String userTask, WorkflowCallbackService.CreatedCallback callback) {
    return userTask.trim()
        + "\n\n"
        + "Callback instructions for reporting completion or failure:\n"
        + callback.callbackInstructions()
        + "\n\n"
        + "Important: call the callback exactly once when the task is complete or failed. Then"
        + " include enough summary/detail/artifact links in the callback payload for the assistant"
        + " to report back to the user.";
  }

  private String scheduleFallback(
      ToolContext context,
      StartCoderAsyncTaskRequest request,
      WorkflowCallbackService.CreatedCallback callback,
      String coderResult,
      ObjectMapper mapper) {
    if (cadenceWorkflowLauncher == null) {
      return "not configured";
    }
    try {
      ObjectNode args = mapper.createObjectNode();
      args.put(
          "delaySeconds",
          positiveOrDefault(request.fallbackDelaySeconds(), DEFAULT_FALLBACK_DELAY_SECONDS));
      args.put(
          "task",
          "Check the Coder async task started by start_coder_async_task. "
              + "Original user request: "
              + request.task().trim()
              + "\nCallback id: "
              + callback.callbackId()
              + "\nCallback expires at: "
              + callback.expiresAt()
              + "\nCoder task creation result: "
              + truncate(coderResult)
              + "\n"
              + "Use the available Coder status/log tools to check progress. If the task is still"
              + " pending or running, call schedule_event again for another one-time check before"
              + " ending the turn. If it completed, fetch enough result/log detail and notify the"
              + " user. If it failed, notify the user with the error.");
      return new ScheduledEventTool(cadenceWorkflowLauncher)
          .getTool()
          .handler()
          .apply(context, args);
    } catch (Exception e) {
      return "failed to schedule fallback: " + e.getMessage();
    }
  }

  private boolean isCoderError(String coderResult, ObjectMapper mapper) {
    try {
      return mapper.readTree(coderResult).path("is_error").asBoolean(false);
    } catch (Exception e) {
      return false;
    }
  }

  private String templateSummaries(List<JsonNode> templates) {
    List<String> summaries = new ArrayList<>();
    for (JsonNode template : templates) {
      summaries.add(
          StringUtils.defaultIfBlank(
                  text(template, "display_name", "displayName"), text(template, "name"))
              + " (active_version_id="
              + text(template, "active_version_id", "activeVersionId")
              + ")");
    }
    return String.join(", ", summaries);
  }

  private static long positiveOrDefault(@Nullable Long value, long defaultValue) {
    return value == null || value <= 0L ? defaultValue : value;
  }

  private static String summarize(String text) {
    String singleLine = StringUtils.defaultString(StringUtils.normalizeSpace(text));
    return singleLine.length() <= 120 ? singleLine : singleLine.substring(0, 117) + "...";
  }

  private static String truncate(String text) {
    if (text == null || text.length() <= MAX_INCLUDED_RESULT_CHARS) {
      return text;
    }
    return text.substring(0, MAX_INCLUDED_RESULT_CHARS - 3) + "...";
  }

  private static String text(JsonNode node, String... names) {
    if (node == null || names == null) {
      return "";
    }
    for (String name : names) {
      JsonNode value = node.get(name);
      if (value != null && !value.isNull()) {
        return value.asText("");
      }
    }
    return "";
  }

  private static String idempotencyKey(
      ToolContext context, String accountId, StartCoderAsyncTaskRequest request) {
    String chatGuid =
        context.message() != null
            ? context.message().chatGuid()
            : context.workflowContext() == null ? "" : context.workflowContext().chatGuid();
    String messageGuid =
        context.message() != null
            ? context.message().messageGuid()
            : context.workflowContext() == null ? "" : context.workflowContext().messageGuid();
    String naturalKey =
        StringUtils.isNotBlank(messageGuid)
            ? accountId + "|" + StringUtils.defaultIfBlank(chatGuid, "") + "|" + messageGuid
            : accountId
                + "|"
                + StringUtils.defaultIfBlank(chatGuid, "")
                + "|"
                + normalizeTask(request.task());
    return "coder-task-" + sha256(naturalKey);
  }

  private static String normalizeTask(String task) {
    return StringUtils.defaultString(StringUtils.normalizeSpace(task)).toLowerCase(Locale.ROOT);
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private record TemplateSelection(String templateVersionId, String displayName) {}
}

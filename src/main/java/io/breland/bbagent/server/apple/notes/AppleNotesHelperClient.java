package io.breland.bbagent.server.apple.notes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AppleNotesHelperClient implements AutoCloseable {
  public static final String DEFAULT_HELPER = "apple-notes-helper";
  public static final String DEFAULT_BACKEND = "auto";

  private final ObjectMapper mapper;
  private final Process process;
  private final BufferedWriter stdin;
  private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending =
      new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private AppleNotesHelperClient(ObjectMapper mapper, Process process, BufferedWriter stdin) {
    this.mapper = mapper;
    this.process = process;
    this.stdin = stdin;
  }

  public static AppleNotesHelperClient start(ObjectMapper mapper) throws IOException {
    return start(mapper, Path.of(DEFAULT_HELPER), DEFAULT_BACKEND);
  }

  public static AppleNotesHelperClient start(ObjectMapper mapper, Path helperPath)
      throws IOException {
    return start(mapper, helperPath, DEFAULT_BACKEND);
  }

  public static AppleNotesHelperClient start(ObjectMapper mapper, Path helperPath, String backend)
      throws IOException {
    Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(helperPath, "helperPath");
    Objects.requireNonNull(backend, "backend");

    Process process =
        new ProcessBuilder(helperPath.toString(), "--stdio", "--backend", backend)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start();
    BufferedWriter stdin =
        new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    AppleNotesHelperClient client = new AppleNotesHelperClient(mapper, process, stdin);

    Thread stdoutReader = new Thread(client::readStdout, "apple-notes-helper-stdout");
    Thread stderrReader = new Thread(client::readStderr, "apple-notes-helper-stderr");
    stdoutReader.setDaemon(true);
    stderrReader.setDaemon(true);
    stdoutReader.start();
    stderrReader.start();

    return client;
  }

  public CompletableFuture<JsonNode> request(String op) {
    return request(op, mapper.createObjectNode());
  }

  public CompletableFuture<JsonNode> request(String op, ObjectNode params) {
    return sendRequest(op, params, false);
  }

  public CompletableFuture<JsonNode> ping() {
    return request("helper.ping");
  }

  public CompletableFuture<Capabilities> capabilities() {
    return request("helper.capabilities").thenApply(result -> convert(result, Capabilities.class));
  }

  public CompletableFuture<List<Account>> listAccounts() {
    return request("accounts.list")
        .thenApply(result -> convert(result, new TypeReference<List<Account>>() {}));
  }

  public CompletableFuture<List<NoteSummary>> listNotes(
      String account, String folder, boolean sharedOnly) {
    ObjectNode params = mapper.createObjectNode();
    putIfPresent(params, "account", account);
    putIfPresent(params, "folder", folder);
    params.put("sharedOnly", sharedOnly);
    return request("notes.list", params)
        .thenApply(result -> convert(result, new TypeReference<List<NoteSummary>>() {}));
  }

  public CompletableFuture<Note> getNote(String id) {
    ObjectNode params = mapper.createObjectNode();
    params.put("id", id);
    return request("notes.get", params).thenApply(result -> convert(result, Note.class));
  }

  public CompletableFuture<CreateNoteResult> createNote(CreateNoteRequest request) {
    ObjectNode params = mapper.createObjectNode();
    putIfPresent(params, "account", request.account());
    putIfPresent(params, "folder", request.folder());
    putIfPresent(params, "title", request.title());
    params.put("html", request.html());
    putPaths(params, "attachments", request.attachments());
    return request("notes.create", params)
        .thenApply(result -> convert(result, CreateNoteResult.class));
  }

  public CompletableFuture<JsonNode> updateNote(String id, String title, String html) {
    return updateNote(id, title, html, List.of());
  }

  public CompletableFuture<JsonNode> updateNote(
      String id, String title, String html, List<Path> attachments) {
    ObjectNode params = mapper.createObjectNode();
    params.put("id", id);
    putIfPresent(params, "title", title);
    putIfPresent(params, "html", html);
    putPaths(params, "attachments", attachments);
    return request("notes.update", params);
  }

  public CompletableFuture<JsonNode> deleteNote(String id) {
    ObjectNode params = mapper.createObjectNode();
    params.put("id", id);
    return request("notes.delete", params);
  }

  public CompletableFuture<ShareResult> shareNote(String noteId, String invitee) {
    return shareNote(noteId, invitee, null, null);
  }

  public CompletableFuture<ShareResult> shareNote(
      String noteId, String invitee, String backend, Duration timeout) {
    ObjectNode params = mapper.createObjectNode();
    params.put("noteId", noteId);
    params.put("invitee", invitee);
    putIfPresent(params, "backend", backend);
    if (timeout != null) {
      params.put("timeout", timeout.toSeconds());
    }
    return request("shares.create", params).thenApply(result -> convert(result, ShareResult.class));
  }

  public CompletableFuture<ShareAcceptResult> acceptShare(String url, Duration timeout) {
    ObjectNode params = mapper.createObjectNode();
    params.put("url", url);
    if (timeout != null) {
      params.put("timeout", timeout.toSeconds());
    }
    return request("shares.accept", params)
        .thenApply(result -> convert(result, ShareAcceptResult.class));
  }

  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    try {
      sendRequest("helper.shutdown", mapper.createObjectNode(), true)
          .orTimeout(2, TimeUnit.SECONDS)
          .exceptionally(_ignored -> null)
          .join();
    } finally {
      synchronized (stdin) {
        stdin.close();
      }
      if (process.isAlive()) {
        process.destroy();
      }
      failPending(new IOException("apple notes helper closed"));
    }
  }

  private CompletableFuture<JsonNode> sendRequest(
      String op, ObjectNode params, boolean allowClosed) {
    if (!allowClosed && closed.get()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("apple notes helper is closed"));
    }

    String id = UUID.randomUUID().toString();
    ObjectNode request = mapper.createObjectNode();
    request.put("id", id);
    request.put("version", 1);
    request.put("op", op);
    request.set("params", params == null ? mapper.createObjectNode() : params);

    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    pending.put(id, future);
    try {
      synchronized (stdin) {
        stdin.write(mapper.writeValueAsString(request));
        stdin.newLine();
        stdin.flush();
      }
    } catch (IOException e) {
      pending.remove(id);
      future.completeExceptionally(e);
    }
    return future;
  }

  private void readStdout() {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        handleResponseLine(line);
      }
    } catch (IOException e) {
      failPending(e);
    } finally {
      failPending(new IOException("apple notes helper stdout closed"));
    }
  }

  private void readStderr() {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
      while (reader.readLine() != null) {
        // Drain stderr so helper diagnostics cannot block the process.
      }
    } catch (IOException ignored) {
      // Closing the helper can close stderr while the drain thread is blocked.
    }
  }

  private void handleResponseLine(String line) {
    try {
      JsonNode response = mapper.readTree(line);
      String id = response.path("id").asText(null);
      if (id == null) {
        failPending(new AppleNotesHelperException("invalid.response", line, false, response));
        return;
      }
      CompletableFuture<JsonNode> future = pending.remove(id);
      if (future == null) {
        return;
      }
      if (response.path("ok").asBoolean(false)) {
        future.complete(response.path("result"));
      } else {
        JsonNode error = response.path("error");
        future.completeExceptionally(
            new AppleNotesHelperException(
                error.path("code").asText("internal"),
                error.path("message").asText("Apple Notes helper request failed"),
                error.path("retryable").asBoolean(false),
                error.path("details")));
      }
    } catch (Exception e) {
      failPending(e);
    }
  }

  private void failPending(Throwable error) {
    pending.forEach((_id, future) -> future.completeExceptionally(error));
    pending.clear();
  }

  private <T> T convert(JsonNode node, Class<T> type) {
    return mapper.convertValue(node, type);
  }

  private <T> T convert(JsonNode node, TypeReference<T> type) {
    return mapper.convertValue(node, type);
  }

  private static void putIfPresent(ObjectNode node, String field, String value) {
    if (value != null && !value.isBlank()) {
      node.put(field, value);
    }
  }

  private static void putPaths(ObjectNode node, String field, List<Path> paths) {
    if (paths == null || paths.isEmpty()) {
      return;
    }
    var array = node.putArray(field);
    paths.forEach(path -> array.add(path.toString()));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Capabilities(
      int protocolVersion, String backend, JsonNode capabilities, JsonNode diagnostics) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Account(String name) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record NoteSummary(
      String id,
      String title,
      String name,
      String folder,
      String createdAt,
      String modifiedAt,
      boolean passwordProtected,
      boolean shared) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Note(
      String id,
      String title,
      String name,
      String folder,
      String html,
      String body,
      String plaintext,
      String createdAt,
      String modifiedAt,
      boolean passwordProtected,
      boolean shared) {}

  public record CreateNoteRequest(
      String account, String folder, String title, String html, List<Path> attachments) {
    public CreateNoteRequest(String account, String folder, String title, String html) {
      this(account, folder, title, html, List.of());
    }

    public CreateNoteRequest {
      Objects.requireNonNull(html, "html");
      attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CreateNoteResult(String id, String title) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShareResult(
      String status,
      String backend,
      String note,
      String email,
      @JsonProperty("share_url") String shareUrl,
      @JsonProperty("shared_after") boolean sharedAfter,
      @JsonProperty("saved_participant_count") int savedParticipantCount,
      @JsonProperty("participant_acceptance_status") int participantAcceptanceStatus,
      @JsonProperty("participant_permission") int participantPermission) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShareAcceptResult(
      String status,
      String backend,
      String object,
      String url,
      @JsonProperty("result_path") String resultPath) {}

  public static final class AppleNotesHelperException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final JsonNode details;

    public AppleNotesHelperException(
        String code, String message, boolean retryable, JsonNode details) {
      super(message);
      this.code = code;
      this.retryable = retryable;
      this.details = details;
    }

    public String code() {
      return code;
    }

    public boolean retryable() {
      return retryable;
    }

    public JsonNode details() {
      return details;
    }
  }
}

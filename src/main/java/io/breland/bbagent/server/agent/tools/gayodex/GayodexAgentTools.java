package io.breland.bbagent.server.agent.tools.gayodex;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.functionParameters;
import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.objectSchema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.tools.gayodex.GayodexClient.Failure;
import io.breland.bbagent.server.agent.tools.gayodex.GayodexClient.Operation;
import io.breland.bbagent.server.agent.tools.wallart.WallartImageInputs;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GayodexAgentTools implements ToolProvider {
  public static final String READ = "get_my_gayodex_profile";
  public static final String CREATE = "create_my_gayodex_profile";
  public static final String UPDATE = "update_my_gayodex_profile";
  public static final String PHOTO = "upload_my_gayodex_photo";
  public static final String JOB = "get_my_gayodex_photo_job";
  public static final Set<String> TOOL_NAMES = Set.of(READ, CREATE, UPDATE, PHOTO, JOB);
  private static final int MAX_PHOTO_BYTES = 6 * 1024 * 1024;
  private static final String TEXT_CHARS =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,!?-:/+()'&%#=<>_";
  private static final Map<String, Integer> TEXT_LIMITS =
      Map.of("name", 18, "archetype", 12, "subtitle", 27, "bio", 135);
  private static final Set<String> PUBLIC_FIELDS =
      Set.of("name", "age", "archetype", "subtitle", "bio", "interests");
  private static final String SCOPE =
      " Only the current verified phone sender's entry in the shared Gayodex; no login or account linking needed. Never edit another person or choose a catalog. ";
  private static final String WRITE_GUIDANCE =
      "Read get_my_gayodex_profile before every write and use its revision. Save only changes the sender requested. On conflict, read and reconsider; never blindly retry. All changes are drafts: tell the sender the owner must publish before they reach a handheld.";
  private final GayodexClient client;
  private final BBHttpClientWrapper blueBubbles;
  private final ObjectMapper mapper;

  public boolean isAllowed(IncomingMessage message) {
    return client.isAllowed(message);
  }

  @Override
  public AgentTool getTool() {
    return tool(
        READ,
        "Read my Gayodex profile and entry revision, including name, age, bio, interests and photo status, or find out whether I have an entry."
            + SCOPE,
        Operation.READ,
        Map.of(),
        List.of());
  }

  public List<AgentTool> getTools() {
    var revision =
        Map.of(
            "type",
            "string",
            "pattern",
            "^[a-f0-9]{64}$",
            "description",
            "Entry revision returned by the most recent get_my_gayodex_profile read.");
    return List.of(
        getTool(),
        tool(
            CREATE,
            "Create my Gayodex profile when no entry is bound to my phone."
                + SCOPE
                + WRITE_GUIDANCE,
            Operation.CREATE,
            Map.of("expected_revision", revision, "profile", profileSchema(false)),
            List.of("expected_revision", "profile")),
        tool(
            UPDATE,
            "Update my Gayodex profile's supplied public fields; photo:null clears my photo."
                + SCOPE
                + WRITE_GUIDANCE,
            Operation.UPDATE,
            Map.of("expected_revision", revision, "changes", profileSchema(true)),
            List.of("expected_revision", "changes")),
        tool(
            PHOTO,
            "Use an image attached to this incoming message as my Gayodex portrait. Omit photo selectors when exactly one current image is attached. For multiple images use image_index from current_message_photos when the current request identifies which photo. If ambiguous, describe the choices and ask for the chosen photo attached to a new message. Never ask the sender for transport IDs. Defaults to asynchronous 8-bit conversion. Poll only queued/processing jobs with a returned ID. A terminal fallback can have no job ID: the original is already saved to the draft; do not poll or ask the sender for an ID. Never use another participant's photo, history images, remote URLs or generated base64."
                + SCOPE
                + WRITE_GUIDANCE,
            Operation.PHOTO,
            Map.of(
                "expected_revision",
                revision,
                "attachment_guid",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "Optional alternative to image_index: attachment GUID from current_message_images/current_message_photos only. Omit for a single current image."),
                "image_index",
                Map.of(
                    "type",
                    "integer",
                    "minimum",
                    1,
                    "description",
                    "Optional one-based image_index from the current message's photo choices; do not combine with attachment_guid."),
                "pixelify",
                Map.of("type", "boolean", "default", true)),
            List.of("expected_revision")),
        tool(
            JOB,
            "Check the status of my Gayodex portrait conversion. Poll queued/processing jobs using this tool in the current incoming-message turn. Returns the latest entry revision. Completion remains a draft requiring owner publishing."
                + SCOPE,
            Operation.JOB,
            Map.of(
                "job_id",
                Map.of(
                    "type",
                    "string",
                    "pattern",
                    "^[A-Za-z0-9_-]{1,128}$",
                    "description",
                    "Job ID returned by upload_my_gayodex_photo for my entry.")),
            List.of("job_id")));
  }

  private AgentTool tool(
      String name,
      String description,
      Operation operation,
      Map<String, ?> properties,
      List<String> required) {
    return new AgentTool(
        name,
        description,
        functionParameters(objectSchema(properties, required)),
        false,
        (context, args) -> execute(context, args, operation, properties.keySet(), required));
  }

  private String execute(
      ToolContext context,
      JsonNode args,
      Operation operation,
      Set<String> fields,
      List<String> required) {
    try {
      if (context == null || !isAllowed(context.message()))
        throw new Failure(
            "unavailable_actor",
            "Gayodex requires a verified incoming BlueChat phone message and configured service. Email, scheduled and unsupported contexts cannot use this feature.");
      if (context.workflowContext() != null
          && (!java.util.Objects.equals(
                  context.message().messageGuid(), context.workflowContext().messageGuid())
              || !java.util.Objects.equals(
                  context.message().chatGuid(), context.workflowContext().chatGuid())
              || context.workflowContext().workflowId() != null
                  && context.workflowContext().workflowId().startsWith("scheduled")))
        throw new Failure(
            "unavailable_actor", "Gayodex needs the current sender's incoming-message context.");
      validateKeys(args, fields, required);
      ObjectNode body = null;
      if (operation == Operation.CREATE
          || operation == Operation.UPDATE
          || operation == Operation.PHOTO) {
        if (!args.path("expected_revision").isTextual()
            || !args.path("expected_revision").asText().matches("[a-f0-9]{64}"))
          invalid("Read your entry first and use its revision.");
        body =
            mapper
                .createObjectNode()
                .put("expected_revision", args.path("expected_revision").asText());
        if (operation == Operation.PHOTO) {
          if (args.has("pixelify") && !args.path("pixelify").isBoolean())
            invalid("pixelify must be true or false.");
          body.put("image_base64", photo(context.message(), args));
          body.put("pixelify", args.path("pixelify").asBoolean(true));
        } else {
          String field = operation == Operation.CREATE ? "profile" : "changes";
          validateProfile(args.path(field), operation == Operation.UPDATE);
          body.set(field, args.get(field));
        }
      }
      var result =
          client.request(context.message(), operation, body, args.path("job_id").asText(null));
      var output = publicResponse(result, operation);
      if (operation == Operation.READ)
        output.set(
            "current_message_photos",
            IncomingAttachment.imageChoices(context.message().attachments()));
      return mapper.writeValueAsString(output);
    } catch (PhotoSelectionRequired failure) {
      var output =
          mapper
              .createObjectNode()
              .put("error", "photo_selection_required")
              .put(
                  "user_facing_text",
                  "Several photos are attached. Use the current request's position or description to select one; if ambiguous, ask for the chosen photo attached to a new message. Do not ask the sender for a transport ID.");
      output.set("current_message_photos", failure.choices);
      return output.toString();
    } catch (Failure failure) {
      return error(failure.code, failure.getMessage());
    } catch (Exception ignored) {
      return error(
          "unavailable",
          "Gayodex could not confirm the result. Read your entry before another write; do not blindly retry.");
    }
  }

  private String photo(IncomingMessage message, JsonNode args) throws java.io.IOException {
    var images = IncomingAttachment.downloadableImages(message.attachments());
    if (args.has("image_index") && args.has("attachment_guid"))
      invalid("Choose either image_index or attachment_guid, not both.");
    IncomingAttachment attachment;
    if (args.has("image_index")) {
      var index = args.path("image_index");
      if (!index.isIntegralNumber()
          || !index.canConvertToInt()
          || index.intValue() < 1
          || index.intValue() > images.size())
        invalid("Use an image_index from this incoming message's photo choices.");
      attachment = images.get(index.intValue() - 1);
    } else if (args.has("attachment_guid")) {
      var guid = args.path("attachment_guid");
      if (!guid.isTextual() || guid.asText().isBlank())
        invalid("Use a current photo choice, or omit the selector for a single image.");
      attachment =
          images.stream()
              .filter(image -> guid.asText().equals(image.guid()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new Failure(
                          "invalid_attachment",
                          "That photo is not attached to this verified incoming message. Use its current photo choices."));
    } else {
      if (images.isEmpty())
        throw new Failure(
            "no_current_photo",
            "This message has no downloadable photo attached. Attach the photo to a new message asking to use it for your Gayodex entry; earlier images are not authorized for this upload.");
      if (images.size() != 1)
        throw new PhotoSelectionRequired(IncomingAttachment.imageChoices(message.attachments()));
      attachment = images.getFirst();
    }
    Path path = null;
    try {
      path = blueBubbles.getImageAttachment(attachment.guid());
      if (path == null) invalid("The current attachment could not be downloaded.");
      if (Files.size(path) > MAX_PHOTO_BYTES) invalid("Photos must be at most 6 MiB.");
      byte[] bytes;
      try (var input = Files.newInputStream(path)) {
        bytes = input.readNBytes(MAX_PHOTO_BYTES + 1);
      }
      if (bytes.length == 0 || bytes.length > MAX_PHOTO_BYTES)
        invalid("Photos must contain data and be at most 6 MiB.");
      // Existing decoder checks image dimensions before allocating a raster; no remote URL fetch.
      var image = WallartImageInputs.normalizePhoto(bytes);
      if (image.bytes().length > MAX_PHOTO_BYTES) invalid("The normalized photo exceeds 6 MiB.");
      return Base64.getEncoder().encodeToString(image.bytes());
    } catch (IllegalArgumentException ignored) {
      throw new Failure(
          "invalid_attachment",
          "The downloaded photo could not be decoded within 20 million pixels and 6 MiB. BlueBubbles normally converts iPhone HEIC photos; if conversion failed, attach a JPEG or PNG. No photo save was attempted.");
    } finally {
      if (path != null)
        try {
          Files.deleteIfExists(path);
        } catch (java.io.IOException ignored) {
          /* best effort */
        }
    }
  }

  private ObjectNode publicResponse(JsonNode result, Operation operation) {
    if (result == null
        || !result.path("revision").isTextual()
        || !result.path("revision").asText().matches("[a-f0-9]{64}")
        || !result.has("profile"))
      throw new Failure(
          "unavailable",
          "Gayodex returned an unexpected response. Read your entry before another write.");
    var output = mapper.createObjectNode();
    if (result.path("profile").isNull()) output.putNull("profile");
    else {
      var profile = mapper.createObjectNode();
      PUBLIC_FIELDS.forEach(
          field -> {
            if (result.path("profile").has(field))
              profile.set(field, result.path("profile").get(field));
          });
      validateProfile(profile, false);
      profile.put("has_photo", result.path("profile").path("has_photo").asBoolean(false));
      output.set("profile", profile);
    }
    output.put("revision", result.path("revision").asText());
    output.put("requires_owner_publish", true);
    boolean write =
        operation == Operation.CREATE
            || operation == Operation.UPDATE
            || operation == Operation.PHOTO;
    if (write
        && (result.path("profile").isNull() || !result.path("saved_to_draft").asBoolean(false)))
      throw new Failure(
          "unavailable",
          "Gayodex did not confirm a draft save. Read your entry before another write.");
    if (write) output.put("saved_to_draft", true);
    if (operation == Operation.CREATE && result.path("created").asBoolean(false))
      output.put("created", true);
    if (result.path("photo_job").isObject()) {
      var job = mapper.createObjectNode();
      String id = result.path("photo_job").path("id").asText("");
      if (id.matches("[A-Za-z0-9_-]{1,128}")) job.put("id", id);
      String status = result.path("photo_job").path("status").asText("");
      if (Set.of("queued", "processing", "complete", "fallback", "original", "superseded")
          .contains(status)) {
        job.put("status", status);
        job.put(
            "message",
            switch (status) {
              case "queued", "processing" ->
                  "8-bit portrait conversion is pending. Poll this job in the current turn, or ask the sender to text again later.";
              case "complete" ->
                  "8-bit portrait conversion is complete in the draft. The owner must publish it.";
              case "fallback" ->
                  "Conversion was unavailable; the original photo was kept in the draft.";
              case "superseded" -> "Newer edits took precedence and were preserved.";
              default -> "Using the original photo in the draft.";
            });
      }
      output.set(
          "photo_job", job); // Never forward arbitrary upstream messages or media references.
    }
    output.put(
        "user_facing_text",
        write
            ? "Saved your entry to the Gayodex draft. The owner must publish it before it reaches a handheld."
            : "This is your Gayodex draft entry. Changes, including completed photo conversions, need the owner's publish before reaching a handheld.");
    return output;
  }

  private static void validateProfile(JsonNode profile, boolean patch) {
    var allowed = new java.util.HashSet<>(PUBLIC_FIELDS);
    if (patch) allowed.add("photo");
    validateKeys(profile, allowed, patch ? List.of() : List.of("name", "age"));
    if (patch && profile.isEmpty()) invalid("Supply at least one requested public field.");
    profile
        .properties()
        .forEach(
            entry -> {
              String field = entry.getKey();
              JsonNode value = entry.getValue();
              if (TEXT_LIMITS.containsKey(field))
                text(value, TEXT_LIMITS.get(field), field.equals("name"));
              else if (field.equals("age")) {
                if (!value.isIntegralNumber()
                    || !value.canConvertToInt()
                    || value.intValue() < 18
                    || value.intValue() > 120) invalid("age must be an integer from 18 to 120.");
              } else if (field.equals("interests")) {
                if (!value.isArray() || value.size() > 3) invalid("Use at most three interests.");
                value.forEach(interest -> text(interest, 25, false));
              } else if (!value.isNull())
                invalid("Only photo:null can clear a photo; upload using a current attachment.");
            });
  }

  private static void text(JsonNode value, int max, boolean required) {
    if (!value.isTextual()
        || value.asText().length() > max
        || required && value.asText().isBlank()
        || value.asText().chars().anyMatch(c -> TEXT_CHARS.indexOf(c) < 0))
      invalid("Use supported handheld ASCII text within the documented field limits.");
  }

  private static void validateKeys(JsonNode args, Set<String> fields, List<String> required) {
    if (args == null
        || !args.isObject()
        || args.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))
        || required.stream().anyMatch(field -> !args.has(field)))
      invalid(
          "Use only the documented fields; identity, catalog, profile selectors and owner-private fields are not accepted.");
  }

  private static Map<String, Object> profileSchema(boolean patch) {
    Map<String, Object> properties = new LinkedHashMap<>();
    TEXT_LIMITS.forEach(
        (field, max) ->
            properties.put(
                field,
                Map.of(
                    "type",
                    "string",
                    "maxLength",
                    max,
                    "description",
                    "Supported handheld ASCII text only.")));
    properties.put("age", Map.of("type", "integer", "minimum", 18, "maximum", 120));
    properties.put(
        "interests",
        Map.of("type", "array", "maxItems", 3, "items", Map.of("type", "string", "maxLength", 25)));
    if (patch)
      properties.put("photo", Map.of("type", "null", "description", "Clear your existing photo."));
    return objectSchema(properties, patch ? List.of() : List.of("name", "age"));
  }

  private static void invalid(String message) {
    throw new Failure("invalid_request", message);
  }

  private String error(String code, String message) {
    return mapper.createObjectNode().put("error", code).put("user_facing_text", message).toString();
  }

  private static final class PhotoSelectionRequired extends RuntimeException {
    private final JsonNode choices;

    private PhotoSelectionRequired(JsonNode choices) {
      this.choices = choices;
    }
  }
}

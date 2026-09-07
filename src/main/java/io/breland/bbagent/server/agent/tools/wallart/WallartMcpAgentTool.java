package io.breland.bbagent.server.agent.tools.wallart;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.functionParameters;
import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolHandler;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.tools.wallart.WallartImageInputs.ImageReference;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class WallartMcpAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "showNewArt";
  public static final String GET_CURRENT_ART = "getCurrentArt";
  public static final String SHOW_IMAGE = "showImage";
  public static final String COMPOSE_ART = "composeArt";
  public static final String GET_ART_STATUS = "getArtStatus";
  public static final String LIST_CONTACT_PHOTOS = "listWallartContactPhotos";
  public static final Set<String> TOOL_NAMES =
      Set.of(
          TOOL_NAME, GET_CURRENT_ART, SHOW_IMAGE, COMPOSE_ART, GET_ART_STATUS, LIST_CONTACT_PHOTOS);
  private static final String SUBMISSION_GUIDANCE =
      " Returns an asynchronous submission, not completion. Use getArtStatus with the returned workflowId; only COMPLETED confirms server deployment, not physical screen state.";

  private final WallartMcpClient wallartMcpClient;
  private final WallartConversationAccess conversationAccess;
  private final WallartImageInputs imageInputs;
  private final BBHttpClientWrapper blueBubbles;

  public record ShowNewArtRequest(
      @Schema(
              description =
                  "Optional art prompt. Omit or leave blank for a fresh random prompt tournament.")
          String prompt) {}

  public record ShowImageRequest(ImageReference image) {}

  public record ComposeArtRequest(String prompt, List<ImageReference> images) {}

  public record ArtStatusRequest(
      @Schema(
              description = "Workflow ID returned by showNewArt, showImage or composeArt.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String workflowId) {}

  public boolean isAllowed(IncomingMessage message) {
    return conversationAccess.isAllowed(message);
  }

  @Override
  public AgentTool getTool() {
    return guarded(
        TOOL_NAME,
        "Generate and display new art on the dining room LED wall. Omit prompt for a fresh random tournament. For photos or edits use composeArt."
            + SUBMISSION_GUIDANCE,
        jsonSchema(ShowNewArtRequest.class),
        (context, args) -> {
          ShowNewArtRequest request =
              context.getMapper().convertValue(args, ShowNewArtRequest.class);
          return wallartMcpClient.showNewArt(
              request == null ? null : StringUtils.trimToNull(request.prompt()));
        });
  }

  public List<AgentTool> getTools() {
    return List.of(
        getTool(),
        guarded(
            LIST_CONTACT_PHOTOS,
            "List names, participant references and photo availability for the current chat's contacts. Use before composing a group portrait, composite of everyone's faces, or art from contact/profile photos. Uses server contacts then shared iMessage profiles when available. Returns metadata only. Missing, unsupported or unavailable photos require attachments or explicit permission to omit those people; never silently leave someone out. Photos may be avatars or Memoji rather than faces.",
            functionParameters(objectSchema(Map.of(), List.of())),
            (context, args) ->
                io.breland.bbagent.server.agent.tools.ToolJson.stringify(
                    context.getMapper(),
                    Map.of(
                        "participants",
                        imageInputs.contactPhotoSummaries(context.message()),
                        "max_images",
                        16),
                    "failed: Unable to list contact photos")),
        guarded(
            GET_CURRENT_ART,
            "Get current dining room LED wall art and send the image as a photo in this chat. This is the stored display image, not a camera screenshot. For editing current art use source=current_art in composeArt; no need to send it first.",
            functionParameters(objectSchema(Map.of(), List.of())),
            (context, args) -> {
              var content = wallartMcpClient.getCurrentArt();
              var image = WallartImageInputs.decode(content.data());
              if (!context.canSendResponses()) return "skipped: outdated workflow";
              if (!context.consumeMessageResponseQuota()) return "rate limited";
              String filename =
                  "current-wallart" + ("image/png".equals(image.mimeType()) ? ".png" : ".jpeg");
              boolean sent =
                  blueBubbles.sendMultipartMessage(
                      context.message().chatGuid(),
                      null,
                      List.of(new BBHttpClientWrapper.AttachmentData(filename, image.bytes())));
              if (!sent)
                return "failed: current art was retrieved but photo delivery failed; do not claim it was sent.";
              context.recordAssistantTurn("[current wall art photo]");
              return "Current wall art photo sent to this conversation. This does not confirm physical screen state.";
            }),
        guarded(
            SHOW_IMAGE,
            "Display a supplied photo on the dining room LED wall without AI generation. Select an attachment reference; the bridge transfers its bytes. PNG/JPEG, up to 10 MiB and 20 million pixels. No resizing; transparency becomes black."
                + SUBMISSION_GUIDANCE,
            functionParameters(
                objectSchema(Map.of("image", imageReferenceSchema()), List.of("image"))),
            (context, args) -> {
              ShowImageRequest request =
                  context.getMapper().convertValue(args, ShowImageRequest.class);
              if (request == null || request.image() == null)
                throw new IllegalArgumentException("image is required.");
              var images = imageInputs.resolve(context.message(), List.of(request.image()));
              if (images.size() != 1)
                throw new IllegalArgumentException(
                    "showImage requires one photo; use composeArt to combine people.");
              if (!context.canSendResponses()) return "skipped: outdated workflow";
              return wallartMcpClient.showImage(images.getFirst());
            }),
        guarded(
            COMPOSE_ART,
            "Edit or combine photos/reference images with a prompt, then display generated art on the dining room LED wall. Use attachment references from this chat, source=current_art to edit the current artwork, or source=group_icon for this group's current chat photo. For people's contact/profile photos first use listWallartContactPhotos, then source=all_contact_photos for everyone or source=contact_photo with a returned participant for selected people. Never silently omit a missing person. Order is preserved: base scene first, people/details next; label each description and explain placement in prompt. 1-16 PNG/JPEG images, 10 MiB/20 million pixels each, 20 MiB total including server PNG conversion. Never supply base64 or image URLs."
                + SUBMISSION_GUIDANCE,
            functionParameters(
                objectSchema(
                    Map.of(
                        "prompt", Map.of("type", "string", "minLength", 1, "maxLength", 20000),
                        "images",
                            Map.of(
                                "type",
                                "array",
                                "minItems",
                                1,
                                "maxItems",
                                16,
                                "items",
                                imageReferenceSchema())),
                    List.of("prompt", "images"))),
            (context, args) -> {
              ComposeArtRequest request =
                  context.getMapper().convertValue(args, ComposeArtRequest.class);
              String prompt = request == null ? null : StringUtils.trimToNull(request.prompt());
              if (prompt == null || prompt.length() > 20000)
                throw new IllegalArgumentException("prompt must contain 1 to 20000 characters.");
              var images = imageInputs.resolve(context.message(), request.images());
              if (!context.canSendResponses()) return "skipped: outdated workflow";
              return wallartMcpClient.composeArt(prompt, images);
            }),
        guarded(
            GET_ART_STATUS,
            "Check an art workflow's status. RUNNING is pending; COMPLETED means server deployment finished. FAILED, TIMED_OUT, CANCELED, TERMINATED are unsuccessful. Never claim a pending submission is displayed. This does not confirm physical screen state.",
            jsonSchema(ArtStatusRequest.class),
            (context, args) -> {
              ArtStatusRequest request =
                  context.getMapper().convertValue(args, ArtStatusRequest.class);
              String id = request == null ? null : StringUtils.trimToNull(request.workflowId());
              if (id == null) throw new IllegalArgumentException("workflowId is required.");
              return wallartMcpClient.getArtStatus(id);
            }));
  }

  private AgentTool guarded(
      String name,
      String description,
      com.openai.models.responses.FunctionTool.Parameters parameters,
      ToolHandler handler) {
    return new AgentTool(
        name,
        description,
        parameters,
        false,
        (context, args) -> {
          if (!isAllowed(context.message()))
            return "The wallart tool is not available in this conversation.";
          if (!context.canSendResponses()) return "skipped: outdated workflow";
          try {
            return handler.apply(context, args);
          } catch (IllegalArgumentException e) {
            // Jackson conversion errors may quote the input. Only expose our own validation
            // messages.
            if (e.getCause() == null) return "error: " + e.getMessage();
            return "error: Invalid wallart arguments. Use image references, not image bytes or URLs.";
          } catch (RuntimeException e) {
            log.warn("Wallart MCP {} call failed: {}", name, e.getClass().getSimpleName());
            return "failed: The wallart request could not be confirmed. A submission may have been accepted; do not automatically retry or claim completion.";
          }
        });
  }

  private static Map<String, Object> imageReferenceSchema() {
    return objectSchema(
        Map.of(
            "source",
                Map.of(
                    "type",
                    "string",
                    "enum",
                    List.of(
                        "attachment",
                        "current_art",
                        "group_icon",
                        "contact_photo",
                        "all_contact_photos"),
                    "description",
                    "attachment selects a chat photo; current_art fetches wall art; group_icon fetches this group's chat photo; contact_photo selects a participant; all_contact_photos expands to one photo per person in this chat."),
            "participant",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "For contact_photo only: participant address returned by listWallartContactPhotos. Never guess or use a person outside this chat."),
            "index",
                Map.of(
                    "type",
                    "integer",
                    "minimum",
                    1,
                    "description",
                    "One-based index among image attachments in the selected message; defaults to 1. Omit for current_art or group_icon."),
            "messageGuid",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "Optional message GUID from this chat's history. Defaults to the incoming message, or the replied-to message if no attachments. Omit for current_art or group_icon. Never guess GUIDs."),
            "description",
                Map.of(
                    "type",
                    "string",
                    "maxLength",
                    500,
                    "description",
                    "Optional role, e.g. base scene, person on left.")),
        List.of("source"));
  }

  private static Map<String, Object> objectSchema(
      Map<String, Object> properties, List<String> required) {
    return Map.of(
        "type",
        "object",
        "properties",
        properties,
        "required",
        required,
        "additionalProperties",
        false);
  }
}

package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.jsonSchema;

import com.openai.client.OpenAIClient;
import com.openai.models.images.Image;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImageModel;
import com.openai.models.images.ImagesResponse;
import io.breland.bbagent.server.agent.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class SetGroupIconAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "set_group_icon";
  private final BBHttpClientWrapper bbHttpClientWrapper;
  private final Supplier<OpenAIClient> openAiSupplier;

  @Schema(description = "Generate an image from a prompt and set it as the group icon.")
  public record SetGroupIconRequest(
      @Schema(
              description = "Prompt to generate the icon image.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String prompt) {}

  public SetGroupIconAgentTool(
      BBHttpClientWrapper bbHttpClientWrapper, Supplier<OpenAIClient> openAiSupplier) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.openAiSupplier = openAiSupplier;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Generate an image from a prompt and set it as the current group icon.",
        jsonSchema(SetGroupIconRequest.class),
        false,
        (context, args) -> {
          IncomingMessage message = context.message();
          if (message == null || message.chatGuid() == null || message.chatGuid().isBlank()) {
            return "no chat";
          }
          if (!AgentTool.isGroupMessage(message)) {
            return "not group";
          }
          SetGroupIconRequest request =
              context.getMapper().convertValue(args, SetGroupIconRequest.class);
          String prompt = request.prompt();
          if (prompt == null || prompt.isBlank()) {
            return "missing prompt";
          }
          Optional<byte[]> imageBytes = generateImageBytes(prompt);
          if (imageBytes.isEmpty()) {
            return "no image";
          }
          Path tempPath = null;
          try {
            tempPath = Files.createTempFile("bb-group-icon-", ".png");
            Files.write(tempPath, imageBytes.get());
            boolean success = bbHttpClientWrapper.setConversationIcon(message.chatGuid(), tempPath);
            return success ? "updated" : "failed";
          } catch (Exception e) {
            return "failed";
          } finally {
            if (tempPath != null) {
              try {
                Files.deleteIfExists(tempPath);
              } catch (Exception ignored) {
                // best effort cleanup
              }
            }
          }
        });
  }

  private Optional<byte[]> generateImageBytes(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      return Optional.empty();
    }
    try {
      ImageGenerateParams params =
          ImageGenerateParams.builder()
              .prompt(prompt)
              .model(ImageModel.GPT_IMAGE_1)
              .size(ImageGenerateParams.Size._1024X1024)
              .outputFormat(ImageGenerateParams.OutputFormat.PNG)
              .responseFormat(ImageGenerateParams.ResponseFormat.B64_JSON)
              .build();
      ImagesResponse response = openAiSupplier.get().images().generate(params);
      List<Image> images = response.data().orElse(List.of());
      if (images.isEmpty()) {
        return Optional.empty();
      }
      Image image = images.get(0);
      Optional<byte[]> bytes = resolveImageBytes(image);
      if (bytes.isPresent()) {
        return bytes;
      }
    } catch (Exception ignored) {
      // ignore and fall through
    }
    return Optional.empty();
  }

  private Optional<byte[]> resolveImageBytes(Image image) {
    if (image == null) {
      return Optional.empty();
    }
    if (image.b64Json().isPresent()) {
      try {
        return Optional.of(Base64.getDecoder().decode(image.b64Json().get()));
      } catch (IllegalArgumentException ignored) {
        return Optional.empty();
      }
    }
    if (image.url().isPresent()) {
      return downloadImage(image.url().get());
    }
    return Optional.empty();
  }

  private Optional<byte[]> downloadImage(String url) {
    if (url == null || url.isBlank()) {
      return Optional.empty();
    }
    try (InputStream input = new URL(url).openStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      input.transferTo(output);
      return Optional.of(output.toByteArray());
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }
}

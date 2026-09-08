package io.breland.bbagent.server.agent.tools.bb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.ResponseFunctionToolCall;
import io.breland.bbagent.generated.bluebubblesclient.model.Chat;
import io.breland.bbagent.generated.bluebubblesclient.model.Message;
import io.breland.bbagent.server.agent.AgentToolActivityRunner;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.*;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.metrics.AgentMetricsService;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadConversationImagesAgentToolTest {
  @TempDir Path directory;
  private final ObjectMapper mapper = new ObjectMapper();
  private final BBHttpClientWrapper bb = mock(BBHttpClientWrapper.class);
  private final IncomingMessage incoming =
      new IncomingMessage(
          "any;-;alice@example.com",
          "current",
          null,
          "Use my earlier photo",
          false,
          "iMessage",
          "alice@example.com",
          false,
          Instant.now(),
          List.of(),
          false);

  @Test
  void earlierPhotoBecomesMultimodalToolOutputWithConvertedBytesAndNoTextTruncation()
      throws Exception {
    Path downloaded = directory.resolve("converted");
    var raster = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
    var random = new java.util.Random(42);
    for (int y = 0; y < 256; y++)
      for (int x = 0; x < 256; x++) raster.setRGB(x, y, random.nextInt());
    ImageIO.write(raster, "png", downloaded.toFile());
    byte[] bytes = Files.readAllBytes(downloaded);
    when(bb.getMessage("older"))
        .thenReturn(
            new Message()
                .chats(List.of(new Chat().guid("iMessage;-;alice@example.com")))
                .attachments(List.of(Map.of("guid", "photo", "mimeType", "image/heic"))));
    when(bb.getAttachment("photo")).thenReturn(downloaded);
    ToolContextFactory factory = mock(ToolContextFactory.class);
    when(factory.create(incoming, null)).thenReturn(ToolContextFixture.with(incoming).build());
    AgentToolRegistry registry = mock(AgentToolRegistry.class);
    AgentTool tool = new LoadConversationImagesAgentTool(bb).getTool();
    when(registry.resolveTool(tool.name(), incoming))
        .thenReturn(new AgentToolRegistry.ResolvedTool(tool));
    AgentToolActivityRunner runner =
        new AgentToolActivityRunner(mapper, factory, registry, mock(AgentMetricsService.class));
    var result =
        runner.run(
            ResponseFunctionToolCall.builder()
                .callId("call")
                .name(tool.name())
                .arguments("{\"images\":[{\"messageGuid\":\"older\"}]}")
                .build(),
            incoming,
            null);
    var json = JsonValue.from(result).convert(com.fasterxml.jackson.databind.JsonNode.class);
    assertThat(json.path("output").isArray()).isTrue();
    String imageUrl = json.path("output").get(1).path("image_url").asText();
    assertThat(imageUrl).startsWith("data:image/png;base64,").hasSizeGreaterThan(24000);
    assertThat(java.util.Base64.getDecoder().decode(imageUrl.substring(imageUrl.indexOf(',') + 1)))
        .isEqualTo(bytes);
    assertThat(Files.exists(downloaded)).isFalse();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"iMessage;-;bob@example.com", "SMS;-;alice@example.com", "iMessage;+;group"})
  void rejectsOtherConversationsBeforeDownloading(String chatGuid) {
    when(bb.getMessage("older"))
        .thenReturn(
            new Message()
                .chats(List.of(new Chat().guid(chatGuid)))
                .attachments(List.of(Map.of("guid", "secret", "mimeType", "image/png"))));
    var context = ToolContextFixture.with(incoming).build();
    String result =
        new LoadConversationImagesAgentTool(bb)
            .getTool()
            .handler()
            .apply(
                context,
                mapper.valueToTree(Map.of("images", List.of(Map.of("messageGuid", "older")))));
    assertThat(result).contains("not in this conversation");
    assertThat(context.modelContent()).isEmpty();
    verify(bb, never()).getAttachment(anyString());
  }

  @Test
  void searchWithoutTextFindsUncaptionedPhotosAndReturnsOnlyMetadata() throws Exception {
    when(bb.getObjectMapper()).thenReturn(mapper);
    when(bb.searchConversationHistory(incoming.chatGuid(), null, 20, 0))
        .thenReturn(
            List.of(
                new Message()
                    .guid(UUID.randomUUID())
                    .attachments(
                        List.of(
                            Map.of(
                                "guid",
                                "photo",
                                "mimeType",
                                "image/png",
                                "transferName",
                                "JD.png",
                                "base64",
                                "private-bytes")))));
    String result =
        new SearchConvoHistoryAgentTool(bb)
            .getTool()
            .handler()
            .apply(
                ToolContextFixture.with(incoming).build(),
                mapper.readTree("{\"limit\":20,\"offset\":0}"));
    assertThat(result).contains("photo", "JD.png", "image/png").doesNotContain("private-bytes");
    verify(bb).searchConversationHistory(incoming.chatGuid(), null, 20, 0);
  }

  @Test
  void failedDownloadDoesNotClaimThePhotoIsLoaded() {
    when(bb.getMessage("older"))
        .thenReturn(
            new Message()
                .chats(List.of(new Chat().guid(incoming.chatGuid())))
                .attachments(List.of(Map.of("guid", "photo", "mimeType", "image/png"))));
    var context = ToolContextFixture.with(incoming).build();
    String result =
        new LoadConversationImagesAgentTool(bb)
            .getTool()
            .handler()
            .apply(
                context,
                mapper.valueToTree(Map.of("images", List.of(Map.of("messageGuid", "older")))));
    assertThat(result).startsWith("Error:").contains("not been loaded");
    assertThat(context.modelContent()).isEmpty();
  }
}

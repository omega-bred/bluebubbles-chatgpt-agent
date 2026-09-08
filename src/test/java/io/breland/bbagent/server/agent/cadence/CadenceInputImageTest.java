package io.breland.bbagent.server.agent.cadence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.*;
import io.breland.bbagent.server.agent.*;
import io.breland.bbagent.server.agent.cadence.models.CadenceToolCall;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.blobstore.BlobStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CadenceInputImageTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final AgentResponseCreator creator = mock(AgentResponseCreator.class);
  private final AgentToolActivityRunner runner = mock(AgentToolActivityRunner.class);
  private final BlobStore blobs = new BlobStore();
  private final IncomingMessage message =
      new IncomingMessage(
          "chat",
          "current",
          null,
          "use earlier photo",
          false,
          "iMessage",
          "user",
          false,
          Instant.EPOCH,
          List.of(),
          false);

  @Test
  void toolImagesAreStoredOutsideWorkflowHistoryAndRehydratedForEachModelRequest()
      throws Exception {
    String imageUrl = "data:image/png;base64," + "YWJj".repeat(20_000);
    var output =
        ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput.builder()
                .callId("call")
                .outputOfResponseFunctionCallOutputItemList(
                    List.of(
                        ResponseFunctionCallOutputItem.ofInputImage(
                            ResponseInputImageContent.builder().imageUrl(imageUrl).build())))
                .build());
    when(runner.run(any(), eq(message), isNull())).thenReturn(output);
    var activities = activities(blobs);
    String persisted =
        activities.executeToolCallsJson(
            List.of(new CadenceToolCall("call", "load_conversation_images", "{}")), message, null);
    assertThat(persisted)
        .contains("bluechat-input-image:")
        .doesNotContain("data:image/")
        .hasSizeLessThan(500);
    when(creator.createResponse(anyList(), eq(message), isNull()))
        .thenReturn(mapper.readValue("{\"output\":[]}", Response.class));
    activities.createResponseBundle(persisted, message, null);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ResponseInputItem>> input = ArgumentCaptor.forClass(List.class);
    verify(creator).createResponse(input.capture(), eq(message), isNull());
    assertThat(
            JsonValue.from(input.getValue())
                .convert(com.fasterxml.jackson.databind.JsonNode.class)
                .toString())
        .contains(imageUrl);

    // A worker restart or expired cache gives the model a recoverable retrieval instruction.
    clearInvocations(creator);
    activities(new BlobStore()).createResponseBundle(persisted, message, null);
    verify(creator).createResponse(input.capture(), eq(message), isNull());
    assertThat(
            JsonValue.from(input.getValue())
                .convert(com.fasterxml.jackson.databind.JsonNode.class)
                .toString())
        .contains("input_text", "Reload it with load_conversation_images")
        .doesNotContain("bluechat-input-image:", "input_image", imageUrl);
  }

  private CadenceAgentActivitiesImpl activities(BlobStore store) {
    var outbound = mock(AgentOutboundService.class);
    when(outbound.canSendResponses(null)).thenReturn(true);
    return new CadenceAgentActivitiesImpl(
        new ConversationStateStore(),
        mock(CadenceIncomingMessageHandler.class),
        outbound,
        creator,
        runner,
        mock(ConversationThreadContextRecorder.class),
        mapper,
        mock(AgentPromptBuilder.class),
        mock(MessageTransportRegistry.class),
        store,
        new GeneratedImageExtractor());
  }
}

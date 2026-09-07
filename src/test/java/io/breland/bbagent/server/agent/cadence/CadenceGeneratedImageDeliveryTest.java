package io.breland.bbagent.server.agent.cadence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.Response;
import io.breland.bbagent.server.agent.*;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.blobstore.BlobStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CadenceGeneratedImageDeliveryTest {
  @Test
  void deliversStoredImagesToTheOriginatingChatAfterAnotherModelResponse() throws Exception {
    ObjectMapper json = new ObjectMapper();
    AgentOutboundService outbound = mock(AgentOutboundService.class);
    AgentResponseCreator creator = mock(AgentResponseCreator.class);
    MessageTransportRegistry transports = mock(MessageTransportRegistry.class);
    MessageTransport transport = mock(MessageTransport.class);
    BlobStore blobs = new BlobStore();
    IncomingMessage message =
        new IncomingMessage(
            "any;+;test-chat",
            "message-1",
            null,
            "draw two images",
            false,
            "iMessage",
            "Alice",
            false,
            Instant.parse("2026-09-07T12:00:00Z"),
            List.of(),
            false);
    AgentWorkflowContext context =
        new AgentWorkflowContext(
            "workflow-1", message.chatGuid(), message.messageGuid(), message.timestamp());
    var activities =
        new CadenceAgentActivitiesImpl(
            mock(ConversationStateStore.class),
            mock(CadenceIncomingMessageHandler.class),
            outbound,
            creator,
            mock(AgentToolActivityRunner.class),
            mock(ConversationThreadContextRecorder.class),
            json,
            mock(AgentPromptBuilder.class),
            transports,
            blobs,
            new GeneratedImageExtractor());
    when(outbound.canSendResponses(context)).thenReturn(true);
    when(outbound.consumeMessageResponseQuota(message, context)).thenReturn(true);
    when(transports.resolve(message)).thenReturn(transport);
    when(transport.supportsGeneratedImages()).thenReturn(true);
    when(transport.sendMultipartMessage(eq(message.chatGuid()), eq("Your images"), anyList()))
        .thenReturn(true);
    Response response =
        json.readValue(
            """
        {"output":[
          {"type":"image_generation_call","id":"ig-1","status":"completed","result":"AQID"},
          {"type":"image_generation_call","id":"ig-2","status":"completed","result":"BAUG"}
        ]}
        """,
            Response.class);
    when(creator.createResponse(anyList(), eq(message), eq(context))).thenReturn(response);

    var bundle = activities.createResponseBundle("[]", message, context);
    assertFalse(bundle.responseJson().contains("AQID"));
    assertFalse(bundle.responseJson().contains("BAUG"));
    GeneratedImageResponses retained = new GeneratedImageResponses();
    retained.capture(bundle.responseJson());
    String delivery = retained.takeForDelivery("{\"output\":[]}");
    var result = activities.handleGeneratedImages(delivery, "Your images", message, context);

    assertTrue(result.sentImage());
    assertTrue(result.captionSent());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<BBHttpClientWrapper.AttachmentData>> attachments =
        ArgumentCaptor.forClass(List.class);
    verify(transport)
        .sendMultipartMessage(eq(message.chatGuid()), eq("Your images"), attachments.capture());
    assertEquals(2, attachments.getValue().size());
    assertArrayEquals(new byte[] {1, 2, 3}, attachments.getValue().get(0).bytes());
    assertArrayEquals(new byte[] {4, 5, 6}, attachments.getValue().get(1).bytes());
    assertNull(blobs.getBlob("different-chat", "ig-1"));
    verify(outbound).consumeMessageResponseQuota(message, context);
    verify(transport).supportsGeneratedImages();
    verifyNoMoreInteractions(transport);
  }
}

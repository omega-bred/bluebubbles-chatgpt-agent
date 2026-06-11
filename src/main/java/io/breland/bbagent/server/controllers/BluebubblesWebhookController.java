package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.api.BluebubblesApiController;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInner;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequest;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestData;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequestDataChatsInner;
import io.breland.bbagent.generated.model.BlueBubblesWebhookResponse;
import io.breland.bbagent.server.agent.MessageIngressPipeline;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.bbagent.base-path:}")
@Slf4j
public class BluebubblesWebhookController extends BluebubblesApiController {

  private static final String GROUP_PREFIX = "iMessage;+;chat";
  private static final String GROUP_PREFIX_2 = "any;+;chat";

  @Autowired private MessageIngressPipeline messageIngressPipeline;

  public BluebubblesWebhookController(NativeWebRequest request) {
    super(request);
  }

  @Override
  public ResponseEntity<BlueBubblesWebhookResponse> bluebubblesMessageReceived(
      @Valid @RequestBody BlueBubblesMessageReceivedRequest requestBody) {
    log.info("Incoming Message {}", requestBody);
    if (requestBody == null || requestBody.getType() == null) {
      return badRequest("missing_request_type");
    }
    if (!isMessageEvent(requestBody)) {
      return okStatus(BlueBubblesWebhookResponse.StatusEnum.OK);
    }
    String validationError =
        messageIngressPipeline.validateMessageEventPayload(requestBody.getData());
    if (validationError != null) {
      messageIngressPipeline.captureMalformedPayload(requestBody.getData(), validationError);
      return badRequest(validationError);
    }
    var message = messageIngressPipeline.normalizeWebhookMessage(requestBody.getData());
    if (message != null) {
      if (!messageIngressPipeline.enqueue(message, requestBody.getData())) {
        return okStatus(BlueBubblesWebhookResponse.StatusEnum.DROPPED_QUEUE_FULL);
      }
      return okStatus(BlueBubblesWebhookResponse.StatusEnum.QUEUED);
    }
    return okStatus(BlueBubblesWebhookResponse.StatusEnum.IGNORED);
  }

  private ResponseEntity<BlueBubblesWebhookResponse> okStatus(
      BlueBubblesWebhookResponse.StatusEnum status) {
    return ResponseEntity.ok(new BlueBubblesWebhookResponse().status(status));
  }

  private ResponseEntity<BlueBubblesWebhookResponse> badRequest(String error) {
    return ResponseEntity.badRequest()
        .body(
            new BlueBubblesWebhookResponse()
                .status(BlueBubblesWebhookResponse.StatusEnum.ERROR)
                .error(error));
  }

  private boolean isMessageEvent(BlueBubblesMessageReceivedRequest request) {
    if (BlueBubblesMessageReceivedRequest.TypeEnum.NEW_MESSAGE.equals(request.getType())) {
      return true;
    }
    if (BlueBubblesMessageReceivedRequest.TypeEnum.UPDATED_MESSAGE.equals(request.getType())) {
      return true;
    }
    return false;
  }

  private static boolean isGroupGuid(String chatGuid) {
    return chatGuid.startsWith(GROUP_PREFIX) || chatGuid.startsWith(GROUP_PREFIX_2);
  }

  public static boolean resolveIsGroup(ApiV1ChatChatGuidMessageGet200ResponseDataInner request) {
    if (request == null) {
      return false;
    }
    List<ApiV1ChatChatGuidMessageGet200ResponseDataInnerChatsInner> chats = request.getChats();
    if (chats != null && chats.size() > 1) {
      return true;
    }
    if (request.getGroupTitle() != null && !request.getGroupTitle().isEmpty()) {
      return true;
    }
    if (chats != null
        && !chats.isEmpty()
        && chats.getFirst().getGuid() != null
        && chats.getFirst().getGuid().startsWith(GROUP_PREFIX)) {
      return true;
    }
    return false;
  }

  public static boolean resolveIsGroup(BlueBubblesMessageReceivedRequestData data) {
    @NotNull
    @Valid
    List<@Valid BlueBubblesMessageReceivedRequestDataChatsInner> chats = data.getChats();
    if (chats != null && chats.size() > 1) {
      return true;
    }
    if (data.getGroupTitle() != null && !data.getGroupTitle().isEmpty()) {
      return true;
    }
    if (chats != null
        && !chats.isEmpty()
        && chats.getFirst().getGuid() != null
        && chats.getFirst().getGuid().startsWith(GROUP_PREFIX)) {
      return true;
    }
    return false;
  }
}

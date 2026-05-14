package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.api.BluebubblesApiController;
import io.breland.bbagent.generated.model.BlueBubblesMessageReceivedRequest;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.bbagent.base-path:}")
@Slf4j
public class BluebubblesWebhookController extends BluebubblesApiController {

  private final BBMessageAgent messageAgent;

  public BluebubblesWebhookController(NativeWebRequest request, BBMessageAgent messageAgent) {
    super(request);
    this.messageAgent = messageAgent;
  }

  @Override
  public ResponseEntity<Object> bluebubblesMessageReceived(
      @Valid @RequestBody BlueBubblesMessageReceivedRequest requestBody) {
    log.info("Incoming Message {}", requestBody);
    if (requestBody == null || requestBody.getType() == null) {
      return ResponseEntity.badRequest().build();
    }
    if (!isMessageEvent(requestBody)) {
      return ResponseEntity.ok(Map.of("status", "ok"));
    }
    IncomingMessage message = IncomingMessage.fromBlueBubblesWebhook(requestBody.getData());
    if (message != null) {
      messageAgent.handleIncomingMessage(message);
    }
    return ResponseEntity.ok(Map.of("status", "ok"));
  }

  private boolean isMessageEvent(BlueBubblesMessageReceivedRequest request) {
    return BlueBubblesMessageReceivedRequest.TypeEnum.NEW_MESSAGE.equals(request.getType())
        || BlueBubblesMessageReceivedRequest.TypeEnum.UPDATED_MESSAGE.equals(request.getType());
  }
}

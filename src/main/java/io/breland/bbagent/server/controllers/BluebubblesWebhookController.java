package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.api.BluebubblesApiController;
import io.breland.bbagent.server.agent.BBMessageAgent;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.newsies.base-path:}")
@Slf4j
public class BluebubblesWebhookController extends BluebubblesApiController {

  @Autowired private BBMessageAgent messageAgent;

  public BluebubblesWebhookController(NativeWebRequest request) {
    super(request);
  }

  @Override
  public ResponseEntity<Object> bluebubblesMessageReceived(
      @Valid @RequestBody Map<String, Object> requestBody) {
    messageAgent.handleIncomingMessage(requestBody);
    return ResponseEntity.ok(Map.of("status", "ok"));
  }
}

package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.api.LxmfApiController;
import io.breland.bbagent.generated.model.LxmfMessageReceivedRequest;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.bbagent.base-path:}")
@Slf4j
public class LxmfWebhookController extends LxmfApiController {
  private static final String LXMF_SERVICE = "LXMF";

  private final BBMessageAgent messageAgent;
  private final String webhookSecret;

  public LxmfWebhookController(
      NativeWebRequest request,
      BBMessageAgent messageAgent,
      @Value("${lxmf.webhook.secret}") String webhookSecret) {
    super(request);
    this.messageAgent = messageAgent;
    this.webhookSecret = webhookSecret;
  }

  @Override
  public ResponseEntity<Object> lxmfReceiveMessages(
      @Valid @RequestBody LxmfMessageReceivedRequest requestBody,
      @RequestHeader(value = "X-LXMF-Bridge-Secret", required = false) String xLXMFBridgeSecret) {
    if (!isAuthorized(xLXMFBridgeSecret)) {
      return ResponseEntity.status(401).body(Map.of("status", "unauthorized"));
    }
    if (requestBody == null
        || isBlank(requestBody.getMessageId())
        || isBlank(requestBody.getSourceHash())) {
      return ResponseEntity.badRequest().body(Map.of("status", "bad_request"));
    }
    IncomingMessage message = parseWebhookMessage(requestBody);
    log.info("Incoming LXMF message {}", message);
    messageAgent.handleIncomingMessage(message);
    return ResponseEntity.ok(Map.of("status", "ok"));
  }

  private IncomingMessage parseWebhookMessage(LxmfMessageReceivedRequest requestBody) {
    String sourceHash = normalizeHash(requestBody.getSourceHash());
    String chatGuid = IncomingMessage.transportPrefix(IncomingMessage.TRANSPORT_LXMF, sourceHash);
    Instant timestamp =
        requestBody.getTimestamp() != null ? requestBody.getTimestamp().toInstant() : Instant.now();
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_LXMF,
        chatGuid,
        requestBody.getMessageId(),
        null,
        requestBody.getContent(),
        false,
        LXMF_SERVICE,
        sourceHash,
        false,
        timestamp,
        List.of(),
        false);
  }

  private boolean isAuthorized(String providedSecret) {
    if (isBlank(webhookSecret)) {
      log.warn("LXMF webhook secret is blank; rejecting bridge webhook");
      return false;
    }
    if (providedSecret == null) {
      return false;
    }
    byte[] expected = webhookSecret.getBytes(StandardCharsets.UTF_8);
    byte[] actual = providedSecret.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expected, actual);
  }

  private static String normalizeHash(String value) {
    return value == null ? null : value.trim().toLowerCase();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}

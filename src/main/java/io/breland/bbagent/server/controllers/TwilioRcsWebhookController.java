package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.api.TwilioRcsApiController;
import io.breland.bbagent.server.agent.BBMessageAgent;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.transport.twiliorcs.TwilioRcsAddress;
import io.breland.bbagent.server.agent.transport.twiliorcs.TwilioRcsClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.bbagent.base-path:}")
@Slf4j
public class TwilioRcsWebhookController extends TwilioRcsApiController {
  private static final String TWILIO_RCS_SERVICE = "Twilio RCS";

  private final NativeWebRequest request;
  private final BBMessageAgent messageAgent;
  private final TwilioRcsClient twilioRcsClient;

  public TwilioRcsWebhookController(
      NativeWebRequest request, BBMessageAgent messageAgent, TwilioRcsClient twilioRcsClient) {
    super(request);
    this.request = request;
    this.messageAgent = messageAgent;
    this.twilioRcsClient = twilioRcsClient;
  }

  @Override
  public ResponseEntity<String> twilioRcsReceiveMessages(
      @Valid @RequestParam(value = "MessageSid", required = true) String messageSid,
      @Valid @RequestParam(value = "From", required = true) String from,
      @Valid @RequestParam(value = "To", required = true) String to,
      @RequestHeader(value = "X-Twilio-Signature", required = false) @Nullable
          String xTwilioSignature,
      @Valid @RequestParam(value = "SmsMessageSid", required = false) String smsMessageSid,
      @Valid @RequestParam(value = "AccountSid", required = false) String accountSid,
      @Valid @RequestParam(value = "MessagingServiceSid", required = false)
          String messagingServiceSid,
      @Valid @RequestParam(value = "Body", required = false) String body,
      @Valid @RequestParam(value = "NumMedia", required = false) String numMedia,
      @Valid @RequestParam(value = "ChannelPrefix", required = false) String channelPrefix,
      @Valid @RequestParam(value = "ProfileName", required = false) String profileName) {
    Map<String, String> formParams = requestParameters();
    if (!twilioRcsClient.isValidWebhook(resolveRequestUrl(), formParams, xTwilioSignature)) {
      return jsonError(401, "unauthorized");
    }

    IncomingMessage message =
        parseWebhookMessage(messageSid, from, to, smsMessageSid, body, numMedia, formParams);
    if (message == null) {
      return jsonError(400, "bad_request");
    }
    log.info(
        "Incoming Twilio RCS message sid={} from={} media_count={}",
        message.messageGuid(),
        message.sender(),
        message.attachments().size());
    messageAgent.handleIncomingMessage(message);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_XML)
        .body(twilioRcsClient.emptyMessagingResponse());
  }

  private IncomingMessage parseWebhookMessage(
      String messageSid,
      String from,
      String to,
      String smsMessageSid,
      String body,
      String numMedia,
      Map<String, String> formParams) {
    String resolvedSid =
        StringUtils.firstNonBlank(
            messageSid,
            formParams.get("MessageSid"),
            smsMessageSid,
            formParams.get("SmsMessageSid"));
    String sender =
        TwilioRcsAddress.normalizeEndpoint(StringUtils.firstNonBlank(from, formParams.get("From")));
    if (StringUtils.isBlank(resolvedSid) || StringUtils.isBlank(sender)) {
      return null;
    }
    String chatGuid = IncomingMessage.transportPrefix(IncomingMessage.TRANSPORT_TWILIO_RCS, sender);
    String content = StringUtils.firstNonBlank(body, formParams.get("Body"), "");
    return new IncomingMessage(
        IncomingMessage.TRANSPORT_TWILIO_RCS,
        chatGuid,
        resolvedSid,
        null,
        content,
        false,
        TWILIO_RCS_SERVICE,
        sender,
        false,
        Instant.now(),
        parseAttachments(
            StringUtils.firstNonBlank(numMedia, formParams.get("NumMedia")), formParams),
        false);
  }

  private List<IncomingAttachment> parseAttachments(
      @Nullable String numMedia, Map<String, String> formParams) {
    int mediaCount = parseMediaCount(numMedia);
    if (mediaCount <= 0) {
      return List.of();
    }
    List<IncomingAttachment> attachments = new ArrayList<>();
    for (int i = 0; i < mediaCount; i++) {
      String url = formParams.get("MediaUrl" + i);
      if (StringUtils.isBlank(url)) {
        continue;
      }
      String mimeType = formParams.get("MediaContentType" + i);
      String sid = StringUtils.firstNonBlank(formParams.get("MediaSid" + i), "twilio-media-" + i);
      attachments.add(new IncomingAttachment(sid, mimeType, null, url, null, null));
    }
    return List.copyOf(attachments);
  }

  private int parseMediaCount(@Nullable String numMedia) {
    if (StringUtils.isBlank(numMedia)) {
      return 0;
    }
    try {
      return Math.max(0, Math.min(Integer.parseInt(numMedia.trim()), 10));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private String resolveRequestUrl() {
    String configured = twilioRcsClient.configuredWebhookUrl();
    if (configured != null) {
      return configured;
    }
    HttpServletRequest servletRequest = servletRequest();
    if (servletRequest == null) {
      return null;
    }
    String url = servletRequest.getRequestURL().toString();
    String query = servletRequest.getQueryString();
    return StringUtils.isBlank(query) ? url : url + "?" + query;
  }

  private Map<String, String> requestParameters() {
    HttpServletRequest servletRequest = servletRequest();
    if (servletRequest == null) {
      return Map.of();
    }
    Map<String, String> params = new LinkedHashMap<>();
    servletRequest
        .getParameterMap()
        .forEach(
            (key, values) -> {
              if (values != null && values.length > 0) {
                params.put(key, values[0]);
              }
            });
    return params;
  }

  private @Nullable HttpServletRequest servletRequest() {
    return request == null ? null : request.getNativeRequest(HttpServletRequest.class);
  }

  private ResponseEntity<String> jsonError(int status, String error) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"status\":\"" + error + "\"}");
  }
}

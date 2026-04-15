package io.breland.bbagent.server.controllers;

import io.breland.bbagent.server.agent.workflowcallback.WorkflowCallbackService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class WorkflowCallbackController {
  private final WorkflowCallbackService callbackService;

  public WorkflowCallbackController(WorkflowCallbackService callbackService) {
    this.callbackService = callbackService;
  }

  @PostMapping(
      path = "/api/v1/workflowCallback/receive.workflowCallbacks",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> receiveWorkflowCallback(
      @RequestParam("callback_id") String callbackId,
      @RequestHeader HttpHeaders headers,
      @RequestBody byte[] body) {
    WorkflowCallbackService.ReceiveResult result =
        callbackService.receiveCallback(callbackId, headers, body);
    return ResponseEntity.status(status(result.status())).body(body(result));
  }

  private HttpStatus status(WorkflowCallbackService.ReceiveStatus status) {
    return switch (status) {
      case PROCESSED, DUPLICATE -> HttpStatus.OK;
      case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
      case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
      case NOT_FOUND -> HttpStatus.NOT_FOUND;
      case CONFLICT -> HttpStatus.CONFLICT;
      case EXPIRED -> HttpStatus.GONE;
    };
  }

  private Map<String, Object> body(WorkflowCallbackService.ReceiveResult result) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("status", result.status().name().toLowerCase());
    response.put("message", result.message());
    if (result.workflowId() != null) {
      response.put("workflow_id", result.workflowId());
    }
    if (result.runId() != null) {
      response.put("run_id", result.runId());
    }
    return response;
  }
}

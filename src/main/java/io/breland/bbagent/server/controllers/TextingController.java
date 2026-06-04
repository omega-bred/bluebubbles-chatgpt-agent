package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.model.TextingNumberResponse;
import io.breland.bbagent.server.texting.TextingNumberService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class TextingController {
  private final TextingNumberService textingNumberService;

  public TextingController(TextingNumberService textingNumberService) {
    this.textingNumberService = textingNumberService;
  }

  @GetMapping(
      path = "/api/v1/texting/get.textingNumber",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<TextingNumberResponse> textingGetNumber(HttpServletRequest request) {
    return ResponseEntity.ok(textingNumberService.getPublicNumber(request));
  }
}

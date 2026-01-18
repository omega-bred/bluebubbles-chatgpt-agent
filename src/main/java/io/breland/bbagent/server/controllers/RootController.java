package io.breland.bbagent.server.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${openapi.bbagent.base-path:}")
public class RootController {

  @RequestMapping(method = RequestMethod.GET, value = "/")
  public ResponseEntity<org.springframework.core.io.Resource> rootGet() {
    // Serve the static index.html bundled inside src/main/resources/static.
    org.springframework.core.io.ClassPathResource resource =
        new org.springframework.core.io.ClassPathResource("static/index.html");

    if (!resource.exists()) {
      return ResponseEntity.notFound().build();
    }

    return ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.TEXT_HTML)
        .body(resource);
  }
}

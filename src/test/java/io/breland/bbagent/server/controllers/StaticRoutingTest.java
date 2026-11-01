package io.breland.bbagent.server.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.breland.bbagent.server.config.SecurityConfig;
import io.breland.bbagent.server.exceptions.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest({RootController.class, StaticRoutingTest.FailureController.class})
@Import({
  GlobalExceptionHandler.class,
  SecurityConfig.class,
  StaticRoutingTest.FailureController.class
})
class StaticRoutingTest {
  @Autowired private MockMvc mockMvc;

  @Test
  void robotsAllowsCrawling() throws Exception {
    mockMvc
        .perform(get("/robots.txt"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
        .andExpect(content().string("User-agent: *\nAllow: /\n"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/does-not-exist", "/api/v1/missing/does.notExist", "/assets/missing.js"})
  void missingPathsReturnNotFound(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isNotFound());
  }

  @Test
  void existingHomePageStillLoads() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
  }

  @Test
  void unexpectedFailuresStillReturnInternalServerError() throws Exception {
    mockMvc.perform(get("/test/internal-error")).andExpect(status().isInternalServerError());
  }

  @RestController
  static class FailureController {
    @GetMapping("/test/internal-error")
    String fail() {
      throw new RuntimeException("Test failure");
    }
  }
}

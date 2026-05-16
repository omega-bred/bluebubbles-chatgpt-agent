package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.model.AdminStatsResponse;
import io.breland.bbagent.server.admin.AdminStatsService;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class AdminController {
  private static final Duration DEFAULT_STATS_PERIOD = Duration.ofHours(24);

  private final AdminStatsService adminStatsService;

  public AdminController(AdminStatsService adminStatsService) {
    this.adminStatsService = adminStatsService;
  }

  @GetMapping(path = "/api/v1/admin/get.statistics", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdminStatsResponse> adminGetStatistics(
      @RequestParam(value = "from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(value = "to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to) {
    Instant resolvedTo = to == null ? Instant.now() : to.toInstant();
    Instant resolvedFrom = from == null ? resolvedTo.minus(DEFAULT_STATS_PERIOD) : from.toInstant();
    if (!resolvedFrom.isBefore(resolvedTo)) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(adminStatsService.getStatistics(resolvedFrom, resolvedTo));
  }
}

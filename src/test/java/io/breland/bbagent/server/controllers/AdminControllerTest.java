package io.breland.bbagent.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.breland.bbagent.generated.model.AdminStatsBucket;
import io.breland.bbagent.generated.model.AdminStatsPeriod;
import io.breland.bbagent.generated.model.AdminStatsResponse;
import io.breland.bbagent.server.admin.AdminStatsService;
import io.breland.bbagent.server.config.BBChatGptAgentConfig;
import io.breland.bbagent.server.config.SecurityConfig;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminController.class)
@Import({BBChatGptAgentConfig.class, SecurityConfig.class})
class AdminControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter;

  @MockBean private AdminStatsService adminStatsService;

  @Test
  void adminStatsRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/admin/get.statistics")).andExpect(status().isUnauthorized());
  }

  @Test
  void adminStatsRequiresAdminRole() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/get.statistics").with(jwt().jwt(token -> token.subject("sub-1"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminStatsReturnsStatisticsForAdminRole() throws Exception {
    when(adminStatsService.getStatistics(any(), any()))
        .thenReturn(
            new AdminStatsResponse()
                .period(
                    new AdminStatsPeriod()
                        .from(OffsetDateTime.parse("2026-05-01T00:00:00Z"))
                        .to(OffsetDateTime.parse("2026-05-02T00:00:00Z"))
                        .bucketSize(AdminStatsPeriod.BucketSizeEnum.HOUR))
                .totalMessages(12L)
                .activeUsers(3L)
                .averageMessagesPerUser(4.0)
                .models(List.of())
                .senders(List.of())
                .timeline(
                    List.of(
                        new AdminStatsBucket()
                            .bucketStart(OffsetDateTime.parse("2026-05-01T00:00:00Z"))
                            .bucketEnd(OffsetDateTime.parse("2026-05-01T01:00:00Z"))
                            .messageCount(12L)
                            .activeUsers(3L)
                            .models(List.of()))));

    mockMvc
        .perform(
            get("/api/v1/admin/get.statistics")
                .with(
                    jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_bbagent-admin-role"))
                        .jwt(token -> token.subject("sub-1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.period.from").value("2026-05-01T00:00:00Z"))
        .andExpect(jsonPath("$.timeline[0].bucket_start").value("2026-05-01T00:00:00Z"))
        .andExpect(jsonPath("$.total_messages").value(12))
        .andExpect(jsonPath("$.active_users").value(3));
  }

  @Test
  void jwtConverterMapsKeycloakRoleClaimsToSpringRoleAuthorities() {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("sub-1")
            .claim("realm_access", Map.of("roles", List.of("bbagent-admin-role")))
            .build();

    AbstractAuthenticationToken authentication = jwtAuthenticationConverter.convert(jwt);

    assertThat(authentication).isNotNull();
    assertThat(authentication.getAuthorities())
        .extracting("authority")
        .contains("ROLE_bbagent-admin-role");
  }
}

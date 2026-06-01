package io.breland.bbagent.server.appclip;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnBean(AppClipSessionService.class)
public class AppClipSessionAuthenticationFilter extends OncePerRequestFilter {
  public static final String SESSION_HEADER = "X-App-Clip-Session";

  private final AppClipSessionService sessionService;

  public AppClipSessionAuthenticationFilter(AppClipSessionService sessionService) {
    this.sessionService = sessionService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String sessionToken = request.getHeader(SESSION_HEADER);
    if (SecurityContextHolder.getContext().getAuthentication() == null
        && sessionToken != null
        && !sessionToken.isBlank()) {
      sessionService
          .authenticate(sessionToken)
          .ifPresent(
              session -> {
                Instant now = Instant.now();
                Jwt jwt =
                    new Jwt(
                        "appclip-session",
                        now,
                        session.expiresAt(),
                        Map.of("alg", "none"),
                        Map.of(
                            "sub",
                            "appclip:" + session.accountId(),
                            AppClipSessionService.APP_CLIP_ACCOUNT_ID_CLAIM,
                            session.accountId(),
                            "scope",
                            "appclip"));
                SecurityContextHolder.getContext()
                    .setAuthentication(new JwtAuthenticationToken(jwt));
              });
    }
    filterChain.doFilter(request, response);
  }
}

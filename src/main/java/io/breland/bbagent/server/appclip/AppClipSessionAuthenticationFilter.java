package io.breland.bbagent.server.appclip;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

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
                Map<String, Object> claims = new LinkedHashMap<>();
                claims.put("sub", "appclip:" + session.accountId());
                claims.put(AppClipSessionService.APP_CLIP_ACCOUNT_ID_CLAIM, session.accountId());
                claims.put(AppClipSessionService.APP_CLIP_PURPOSE_CLAIM, session.purpose());
                if (session.chatGuid() != null && !session.chatGuid().isBlank()) {
                  claims.put(AppClipSessionService.APP_CLIP_CHAT_GUID_CLAIM, session.chatGuid());
                }
                claims.put("scope", "appclip");
                Jwt jwt =
                    new Jwt(
                        "appclip-session", now, session.expiresAt(), Map.of("alg", "none"), claims);
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new JwtAuthenticationToken(
                            jwt, List.of(new SimpleGrantedAuthority("SCOPE_appclip"))));
              });
    }
    filterChain.doFilter(request, response);
  }
}

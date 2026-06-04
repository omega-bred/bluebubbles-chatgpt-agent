package io.breland.bbagent.server.nativeapp;

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

public class NativeAppSessionAuthenticationFilter extends OncePerRequestFilter {
  private final NativeAppSessionService sessionService;

  public NativeAppSessionAuthenticationFilter(NativeAppSessionService sessionService) {
    this.sessionService = sessionService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String sessionToken = request.getHeader(NativeAppSessionService.SESSION_HEADER);
    if (SecurityContextHolder.getContext().getAuthentication() == null
        && sessionToken != null
        && !sessionToken.isBlank()) {
      sessionService
          .authenticate(sessionToken)
          .ifPresent(
              session -> {
                Instant now = Instant.now();
                Map<String, Object> claims = new LinkedHashMap<>();
                claims.put("sub", "nativeapp:" + session.accountId());
                claims.put(
                    NativeAppSessionService.NATIVE_APP_ACCOUNT_ID_CLAIM, session.accountId());
                claims.put("scope", "native_app");
                Jwt jwt =
                    new Jwt(
                        "native-app-session",
                        now,
                        session.expiresAt(),
                        Map.of("alg", "none"),
                        claims);
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new JwtAuthenticationToken(
                            jwt, List.of(new SimpleGrantedAuthority("SCOPE_native_app"))));
              });
    }
    filterChain.doFilter(request, response);
  }
}

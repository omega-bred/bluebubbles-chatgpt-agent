package io.breland.bbagent.server.config;

import io.breland.bbagent.server.appclip.AppClipSessionAuthenticationFilter;
import io.breland.bbagent.server.appclip.AppClipSessionService;
import io.breland.bbagent.server.nativeapp.NativeAppSessionAuthenticationFilter;
import io.breland.bbagent.server.nativeapp.NativeAppSessionService;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.DPoPAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
  private static final String ADMIN_ROLE = "bbagent-admin-role";

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter,
      ObjectProvider<AppClipSessionService> appClipSessionService,
      ObjectProvider<NativeAppSessionService> nativeAppSessionService,
      JwtDecoder jwtDecoder,
      DpopReplayStore replayStore,
      @Value("${bluechat.security.dpop.public-origins:}") String publicOrigins,
      @Value("${bluechat.security.cors.allowed-origins:}") String corsOrigins)
      throws Exception {
    DpopAuthenticationSupport dpop =
        new DpopAuthenticationSupport(
            jwtDecoder, jwtAuthenticationConverter, replayStore, publicOrigins);
    AuthenticationEntryPoint entryPoint =
        (request, response, exception) -> {
          String authorization = request.getHeader("Authorization");
          if (authorization != null && authorization.regionMatches(true, 0, "DPoP", 0, 4)) {
            new DPoPAuthenticationEntryPoint().commence(request, response, exception);
          } else {
            new BearerTokenAuthenticationEntryPoint().commence(request, response, exception);
          }
        };
    // Spring's bearer-only filter intentionally rejects every cnf.jkt token. Use the
    // framework's general AuthenticationFilter for the two explicit schemes, keeping
    // the original JWT principal/authorities and our downgrade rejection intact.
    AuthenticationFilter tokenFilter = new AuthenticationFilter(dpop::authenticate, dpop::convert);
    tokenFilter.setSuccessHandler((request, response, authentication) -> {});
    tokenFilter.setFailureHandler(new AuthenticationEntryPointFailureHandler(entryPoint));
    tokenFilter.setSecurityContextRepository(new RequestAttributeSecurityContextRepository());
    http.addFilterAt(tokenFilter, BearerTokenAuthenticationFilter.class);
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(
        java.util.Arrays.stream(corsOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList());
    cors.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
    cors.setAllowedHeaders(
        java.util.List.of(
            "Authorization",
            "DPoP",
            "Content-Type",
            "X-App-Clip-Session",
            "X-BlueChat-App-Session"));
    cors.setExposedHeaders(java.util.List.of("WWW-Authenticate", "DPoP-Nonce"));
    UrlBasedCorsConfigurationSource corsSource = new UrlBasedCorsConfigurationSource();
    corsSource.registerCorsConfiguration("/api/**", cors);
    appClipSessionService.ifAvailable(
        service ->
            http.addFilterBefore(
                new AppClipSessionAuthenticationFilter(service),
                BearerTokenAuthenticationFilter.class));
    nativeAppSessionService.ifAvailable(
        service ->
            http.addFilterBefore(
                new NativeAppSessionAuthenticationFilter(service),
                BearerTokenAuthenticationFilter.class));
    return http.csrf(AbstractHttpConfigurer::disable)
        .cors(c -> c.configurationSource(corsSource))
        .requestCache(AbstractHttpConfigurer::disable)
        .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/api/v1/admin/**")
                    .hasAuthority(roleAuthority(ADMIN_ROLE))
                    .requestMatchers("/api/v1/appClip/createSession.appClipSessions")
                    .permitAll()
                    .requestMatchers("/api/v1/appClip/createBootstrapEvent.appClipEvents")
                    .permitAll()
                    .requestMatchers("/api/v1/appClip/**")
                    .authenticated()
                    .requestMatchers("/api/v1/nativeApp/createSession.nativeAppSessions")
                    .permitAll()
                    .requestMatchers("/api/v1/nativeApp/**")
                    .authenticated()
                    .requestMatchers("/api/v1/texting/get.textingNumber")
                    .permitAll()
                    .requestMatchers("/api/v1/contact/**")
                    .permitAll()
                    .requestMatchers("/api/v1/conversationSettings/**")
                    .authenticated()
                    .requestMatchers("/api/v1/websiteAccount/**")
                    .authenticated()
                    .requestMatchers(
                        "/api/v1/subscription/receiveWebhook.subscriptionProviderEvents")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/subscription/list.subscriptionPlans")
                    .permitAll()
                    .requestMatchers("/api/v1/subscription/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .build();
  }

  @Bean
  DpopReplayStore dpopReplayStore(ObjectProvider<JdbcTemplate> jdbc) {
    return new DpopReplayStore(jdbc);
  }

  @Bean
  Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          Set<GrantedAuthority> authorities = new LinkedHashSet<>();
          Collection<GrantedAuthority> scopeAuthorities = scopesConverter.convert(jwt);
          if (scopeAuthorities != null) {
            authorities.addAll(scopeAuthorities);
          }
          for (String role : roles(jwt)) {
            authorities.add(new SimpleGrantedAuthority(role));
            authorities.add(new SimpleGrantedAuthority(roleAuthority(role)));
          }
          return authorities;
        });
    return converter;
  }

  private Set<String> roles(Jwt jwt) {
    Set<String> roles = new LinkedHashSet<>();
    Object realmAccess = jwt.getClaim("realm_access");
    if (realmAccess instanceof Map<?, ?> realmAccessMap) {
      addRoles(realmAccessMap.get("roles"), roles);
    }
    Object resourceAccess = jwt.getClaim("resource_access");
    if (resourceAccess instanceof Map<?, ?> resourceAccessMap) {
      for (Object clientAccess : resourceAccessMap.values()) {
        if (clientAccess instanceof Map<?, ?> clientAccessMap) {
          addRoles(clientAccessMap.get("roles"), roles);
        }
      }
    }
    return roles;
  }

  private void addRoles(Object value, Set<String> roles) {
    if (!(value instanceof Collection<?> roleValues)) {
      return;
    }
    for (Object role : roleValues) {
      if (role instanceof String roleName && !roleName.isBlank()) {
        roles.add(roleName.trim());
      }
    }
  }

  private static String roleAuthority(String role) {
    return role.startsWith("ROLE_") ? role : "ROLE_" + role;
  }
}

package io.breland.bbagent.server.config;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
  private static final String ADMIN_ROLE = "bbagent-admin-role";

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/api/v1/admin/**")
                    .hasAuthority(roleAuthority(ADMIN_ROLE))
                    .requestMatchers("/api/v1/websiteAccount/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .oauth2ResourceServer(
            oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
        .build();
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

package io.breland.bbagent.server.config;

import org.springframework.boot.SpringBootConfiguration;

@SpringBootConfiguration
// @EnableMethodSecurity // so we can @PreAuthorize later
// @EnableWebSecurity
public class SecurityConfig {

  //  @Value("${clerk.secret-key}")
  //  private String clerkSecretKey;
  //
  //  @Value("${clerk.authorizedParties}")
  //  private List<String> authorizedParties;
  //
  //  @Bean
  //  @Order(1) // evaluated BEFORE userChain
  //  SecurityFilterChain deviceChain(HttpSecurity http) throws Exception {
  //
  //    http.securityMatcher(
  //            "/index.html",
  //            "/",
  //            "/client/*",
  //            "/clerk/*",
  //            "/config",
  //            "/assets/**",
  //            "/ping",
  //            "/current_image.bmp",
  //            "/ota/latest.bin",
  //            "/ota/latest.version",
  //            "/ota",
  //            "/rlog")
  //        .headers(
  //            headers ->
  //                headers.contentSecurityPolicy(
  //                    csp ->
  //                        csp
  //                            // allow your app + Cloudflare Turnstile iframe
  //                            .policyDirectives(
  //                            "frame-src 'self' https://challenges.cloudflare.com;")))
  //        .authorizeHttpRequests(
  //            auth ->
  //                auth.requestMatchers("/**")
  //                    .permitAll()
  //                    .anyRequest()
  //                    .anonymous()) // everything else still locked down
  //        .csrf(AbstractHttpConfigurer::disable);
  //
  //    return http.build();
  //  }
  //
  //  @Bean
  //  public SecurityFilterChain userChain(HttpSecurity http) throws Exception {
  //    http.csrf(csrf -> csrf.disable())
  //        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
  //        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
  //        // insert our Clerk filter before Spring’s UsernamePasswordAuthenticationFilter
  //        .addFilterBefore(
  //            new ClerkAuthenticationFilter(clerkSecretKey, authorizedParties),
  //            UsernamePasswordAuthenticationFilter.class);
  //
  //    return http.build();
  //  }
}

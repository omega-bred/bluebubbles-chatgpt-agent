package io.breland.bbagent.server.config;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.DPoPProofJwtDecoderFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.DPoPAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.DPoPAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.DPoPAuthenticationConverter;

/** Uses Spring's RFC 9449 verifier while keeping legacy, unbound bearer sessions usable. */
public final class DpopAuthenticationSupport {
  private final AuthenticationManager jwtManager;
  private final DPoPAuthenticationProvider dpop;
  private final DPoPAuthenticationConverter dpopConverter = new DPoPAuthenticationConverter();
  private final BearerTokenAuthenticationConverter bearerConverter =
      new BearerTokenAuthenticationConverter();
  private final List<URI> publicOrigins;

  public DpopAuthenticationSupport(
      JwtDecoder decoder,
      Converter<Jwt, AbstractAuthenticationToken> converter,
      DpopReplayStore replayStore,
      String origins) {
    JwtAuthenticationProvider jwt = new JwtAuthenticationProvider(decoder);
    jwt.setJwtAuthenticationConverter(converter);
    jwtManager = jwt::authenticate;
    dpop = new DPoPAuthenticationProvider(jwtManager);
    DPoPProofJwtDecoderFactory proofs = new DPoPProofJwtDecoderFactory();
    var defaults = DPoPProofJwtDecoderFactory.createDefaultJwtValidatorFactory(List.of());
    proofs.setJwtValidatorFactory(
        context ->
            new DelegatingOAuth2TokenValidator<>(
                defaults.apply(context),
                new JwtClaimValidator<>("htm", context.getMethod()::equals)));
    dpop.setDPoPProofVerifierFactory(
        context ->
            token -> {
              // Validate signature, public asymmetric JWK, typ, htm, htu, iat, jti, cnf.jkt and ath
              // before consuming a slot in the shared replay store.
              Jwt proof = proofs.createDecoder(context).decode(token);
              replayStore.consume(proof);
              return proof;
            });
    publicOrigins =
        Arrays.stream(origins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(URI::create)
            .toList();
    for (URI origin : publicOrigins) {
      if (!List.of("https", "http").contains(origin.getScheme())
          || origin.getHost() == null
          || origin.getRawUserInfo() != null
          || origin.getRawQuery() != null
          || origin.getRawFragment() != null
          || !List.of("", "/").contains(origin.getRawPath())) {
        throw new IllegalArgumentException("DPoP public origins must be HTTP(S) origins");
      }
    }
  }

  public Authentication convert(HttpServletRequest request) {
    Authentication authentication = dpopConverter.convert(request);
    if (authentication instanceof DPoPAuthenticationToken proof) {
      return new DPoPAuthenticationToken(
          proof.getAccessToken(), proof.getDPoPProof(), proof.getMethod(), externalUri(request));
    }
    if (request.getHeader("DPoP") != null) {
      throw invalid("invalid_request", "DPoP requires the DPoP authorization scheme");
    }
    return bearerConverter.convert(request);
  }

  public Authentication authenticate(Authentication authentication) {
    if (authentication instanceof DPoPAuthenticationToken) {
      return dpop.authenticate(authentication);
    }
    Authentication result = jwtManager.authenticate(authentication);
    if (result instanceof JwtAuthenticationToken jwt && jwt.getToken().hasClaim("cnf")) {
      throw invalid("invalid_token", "Sender-constrained tokens require a DPoP proof");
    }
    return result;
  }

  private String externalUri(HttpServletRequest request) {
    if (publicOrigins.isEmpty()) {
      return request.getRequestURL().toString();
    }
    // The ingress preserves Host and path. The scheme comes from configuration, never
    // untrusted Forwarded/X-Forwarded-* headers. Only explicitly allowed hosts can match.
    String host = request.getHeader("Host");
    if (java.util.Collections.list(request.getHeaders("Host")).size() != 1) {
      throw invalid("invalid_dpop_proof", "Missing or ambiguous resource host");
    }
    return publicOrigins.stream()
        .filter(origin -> origin.getRawAuthority().equalsIgnoreCase(host))
        .findFirst()
        .map(
            origin ->
                origin.getScheme() + "://" + origin.getRawAuthority() + request.getRequestURI())
        .orElseThrow(() -> invalid("invalid_dpop_proof", "Unrecognized resource host"));
  }

  private static OAuth2AuthenticationException invalid(String code, String description) {
    return new OAuth2AuthenticationException(new OAuth2Error(code, description, null));
  }
}

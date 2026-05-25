package io.breland.bbagent.server.agent.tools.coder;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthCredentialEntity;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthCredentialRepository;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthPendingAuthorizationEntity;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthPendingAuthorizationRepository;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
public class CoderMcpClient {
  private static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(10);
  private static final Duration TOKEN_REFRESH_LEEWAY = Duration.ofMinutes(1);

  private final RestClient restClient;
  private final CoderOauthCredentialRepository credentialRepository;
  private final CoderOauthPendingAuthorizationRepository pendingAuthorizationRepository;
  private final String serverUrl;
  private final String redirectUri;
  private final String configuredClientId;
  private final String configuredClientSecret;
  private final String scopes;
  private final CoderOauthStateCodec stateCodec;

  private volatile ProtectedResourceMetadata protectedResourceMetadata;
  private volatile AuthorizationServerMetadata authorizationServerMetadata;

  public CoderMcpClient(
      @Value("${coder.mcp.server_url:}") String serverUrl,
      @Value("${coder.oauth.redirect_uri:}") String redirectUri,
      @Value("${coder.oauth.state_secret:}") String stateSecret,
      @Value("${coder.oauth.client_id:}") String configuredClientId,
      @Value("${coder.oauth.client_secret:}") String configuredClientSecret,
      @Value("${coder.oauth.scopes:coder:all}") String scopes,
      RestClient.Builder restClientBuilder,
      CoderOauthCredentialRepository credentialRepository,
      CoderOauthPendingAuthorizationRepository pendingAuthorizationRepository) {
    this.serverUrl = serverUrl;
    this.redirectUri = redirectUri;
    this.stateCodec = new CoderOauthStateCodec(stateSecret, OAUTH_STATE_TTL);
    this.configuredClientId = configuredClientId;
    this.configuredClientSecret = configuredClientSecret;
    this.scopes = scopes;
    this.restClient = restClientBuilder.build();
    this.credentialRepository = credentialRepository;
    this.pendingAuthorizationRepository = pendingAuthorizationRepository;
  }

  public boolean isConfigured() {
    return serverUrl != null
        && !serverUrl.isBlank()
        && redirectUri != null
        && !redirectUri.isBlank()
        && configuredClientId != null
        && !configuredClientId.isBlank()
        && stateCodec.isConfigured();
  }

  public boolean isLinked(String accountId) {
    return resolveLinkedAccountId(accountId).isPresent();
  }

  public Optional<String> getAuthUrl(String accountId, String chatGuid, String messageGuid) {
    if (!isConfigured() || accountId == null || accountId.isBlank()) {
      return Optional.empty();
    }
    try {
      pendingAuthorizationRepository.deleteByExpiresAtBefore(Instant.now());
      OAuthClientRegistration registration = ensureClientRegistration();
      AuthorizationServerMetadata authMetadata = getAuthorizationServerMetadata();
      ProtectedResourceMetadata resourceMetadata = getProtectedResourceMetadata();
      String pendingId = UUID.randomUUID().toString();
      String codeVerifier = stateCodec.generateCodeVerifier();
      Optional<String> state = stateCodec.createState(accountId, pendingId, chatGuid, messageGuid);
      if (state.isEmpty()) {
        return Optional.empty();
      }
      Instant now = Instant.now();
      pendingAuthorizationRepository.save(
          new CoderOauthPendingAuthorizationEntity(
              pendingId,
              accountId,
              chatGuid,
              messageGuid,
              codeVerifier,
              now.plus(OAUTH_STATE_TTL),
              now));
      String url =
          UriComponentsBuilder.fromUriString(authMetadata.authorizationEndpoint())
              .queryParam("response_type", "code")
              .queryParam("client_id", registration.clientId())
              .queryParam("redirect_uri", redirectUri)
              .queryParam("scope", scopes)
              .queryParam("state", state.get())
              .queryParam("code_challenge", stateCodec.codeChallenge(codeVerifier))
              .queryParam("code_challenge_method", "S256")
              .queryParam("resource", resourceMetadata.resource())
              .build()
              .encode()
              .toUriString();
      return Optional.of(url);
    } catch (Exception e) {
      log.warn("Failed to create Coder OAuth URL", e);
      return Optional.empty();
    }
  }

  public Optional<OauthCompletion> completeOauth(String code, String state) {
    if (!isConfigured() || code == null || code.isBlank() || state == null || state.isBlank()) {
      return Optional.empty();
    }
    Optional<CoderOauthStateCodec.OauthState> parsedState = stateCodec.parseState(state);
    if (parsedState.isEmpty()) {
      return Optional.empty();
    }
    CoderOauthStateCodec.OauthState oauthState = parsedState.get();
    Optional<CoderOauthPendingAuthorizationEntity> pending =
        pendingAuthorizationRepository.findById(oauthState.pendingId());
    if (pending.isEmpty()) {
      return Optional.empty();
    }
    CoderOauthPendingAuthorizationEntity pendingAuth = pending.get();
    if (pendingAuth.getExpiresAt() == null || pendingAuth.getExpiresAt().isBefore(Instant.now())) {
      pendingAuthorizationRepository.deleteById(pendingAuth.getPendingId());
      return Optional.empty();
    }
    if (!pendingAuth.getAccountId().equals(oauthState.accountId())) {
      return Optional.empty();
    }
    try {
      TokenResponse tokenResponse =
          requestAuthorizationCodeToken(code, pendingAuth.getCodeVerifier());
      saveCredential(oauthState.accountId(), tokenResponse);
      pendingAuthorizationRepository.deleteById(pendingAuth.getPendingId());
      return Optional.of(
          new OauthCompletion(
              oauthState.accountId(), oauthState.chatGuid(), oauthState.messageGuid()));
    } catch (Exception e) {
      log.warn("Failed to complete Coder OAuth", e);
      return Optional.empty();
    }
  }

  public boolean revoke(String accountId) {
    Optional<String> linkedAccountId = resolveLinkedAccountId(accountId);
    if (linkedAccountId.isEmpty()) {
      return false;
    }
    String credentialAccountId = linkedAccountId.get();
    Optional<CoderOauthCredentialEntity> existing =
        credentialRepository.findById(credentialAccountId);
    existing.ifPresent(this::tryRevokeRemote);
    credentialRepository.deleteById(credentialAccountId);
    return existing.isPresent();
  }

  private String getValidAccessToken(String accountId) throws IOException {
    CoderOauthCredentialEntity credential =
        credentialRepository
            .findById(accountId)
            .orElseThrow(() -> new IOException("Coder account is not linked"));
    if (hasUsableAccessToken(credential)) {
      return credential.getAccessToken();
    }
    return refreshCredential(accountId, false)
        .map(CoderOauthCredentialEntity::getAccessToken)
        .orElseThrow(() -> new IOException("Coder token expired; please relink Coder"));
  }

  private Optional<String> resolveLinkedAccountId(String accountId) {
    if (accountId == null || accountId.isBlank()) {
      return Optional.empty();
    }
    if (credentialRepository.existsById(accountId)) {
      return Optional.of(accountId);
    }
    return Optional.empty();
  }

  public Optional<CoderLinkedAccount> findLinkedAccount(String accountId) {
    return resolveLinkedAccountId(accountId)
        .map(
            linkedAccountId -> {
              Optional<CoderUserProfile> profile = getCurrentUserProfile(linkedAccountId);
              return new CoderLinkedAccount(
                  linkedAccountId,
                  profile
                      .map(CoderUserProfile::email)
                      .filter(email -> !email.isBlank())
                      .orElse(null),
                  profile.map(CoderMcpClient::profileLabel).orElse("Coder"));
            });
  }

  private String getValidAccessTokenUnchecked(String accountId) {
    try {
      return getValidAccessToken(accountId);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Optional<CoderUserProfile> getCurrentUserProfile(String accountId) {
    try {
      CoderUserProfile profile =
          restClient
              .get()
              .uri(serverOrigin(URI.create(serverUrl)) + "/api/v2/users/me")
              .accept(MediaType.APPLICATION_JSON)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + getValidAccessToken(accountId))
              .retrieve()
              .body(CoderUserProfile.class);
      return Optional.ofNullable(profile);
    } catch (Exception e) {
      log.warn("Failed to load Coder user profile", e);
      return Optional.empty();
    }
  }

  private static String profileLabel(CoderUserProfile profile) {
    if (profile.name() != null && !profile.name().isBlank()) {
      return profile.name();
    }
    if (profile.username() != null && !profile.username().isBlank()) {
      return profile.username();
    }
    return "Coder";
  }

  private Optional<CoderOauthCredentialEntity> refreshCredential(
      String accountId, boolean forceRefresh) {
    Optional<CoderOauthCredentialEntity> existing = credentialRepository.findById(accountId);
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    CoderOauthCredentialEntity credential = existing.get();
    if (!forceRefresh && hasUsableAccessToken(credential)) {
      return Optional.of(credential);
    }
    if (credential.getRefreshToken() == null || credential.getRefreshToken().isBlank()) {
      return Optional.empty();
    }
    try {
      TokenResponse tokenResponse = requestRefreshToken(credential.getRefreshToken());
      CoderOauthCredentialEntity saved = saveCredential(accountId, tokenResponse);
      return Optional.of(saved);
    } catch (Exception e) {
      log.warn("Failed to refresh Coder OAuth token", e);
      return Optional.empty();
    }
  }

  private boolean hasUsableAccessToken(CoderOauthCredentialEntity credential) {
    if (credential.getAccessToken() == null || credential.getAccessToken().isBlank()) {
      return false;
    }
    Instant expiresAt = credential.getExpiresAt();
    return expiresAt == null || expiresAt.minus(TOKEN_REFRESH_LEEWAY).isAfter(Instant.now());
  }

  private TokenResponse requestAuthorizationCodeToken(String code, String codeVerifier) {
    MultiValueMap<String, String> form = tokenRequestBaseForm();
    form.add("grant_type", "authorization_code");
    form.add("code", code);
    form.add("redirect_uri", redirectUri);
    form.add("code_verifier", codeVerifier);
    return requestToken(form);
  }

  private TokenResponse requestRefreshToken(String refreshToken) {
    MultiValueMap<String, String> form = tokenRequestBaseForm();
    form.add("grant_type", "refresh_token");
    form.add("refresh_token", refreshToken);
    return requestToken(form);
  }

  private MultiValueMap<String, String> tokenRequestBaseForm() {
    OAuthClientRegistration registration = ensureClientRegistration();
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("client_id", registration.clientId());
    if (registration.clientSecret() != null && !registration.clientSecret().isBlank()) {
      form.add("client_secret", registration.clientSecret());
    }
    form.add("resource", getProtectedResourceMetadata().resource());
    return form;
  }

  private TokenResponse requestToken(MultiValueMap<String, String> form) {
    return restClient
        .post()
        .uri(getAuthorizationServerMetadata().tokenEndpoint())
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .accept(MediaType.APPLICATION_JSON)
        .body(form)
        .retrieve()
        .body(TokenResponse.class);
  }

  private CoderOauthCredentialEntity saveCredential(String accountId, TokenResponse tokenResponse) {
    if (tokenResponse == null
        || tokenResponse.accessToken() == null
        || tokenResponse.accessToken().isBlank()) {
      throw new IllegalArgumentException("Token response did not include an access token");
    }
    Instant now = Instant.now();
    CoderOauthCredentialEntity existing = credentialRepository.findById(accountId).orElse(null);
    String refreshToken = tokenResponse.refreshToken();
    if ((refreshToken == null || refreshToken.isBlank()) && existing != null) {
      refreshToken = existing.getRefreshToken();
    }
    Instant expiresAt =
        tokenResponse.expiresIn() == null ? null : now.plusSeconds(tokenResponse.expiresIn());
    CoderOauthCredentialEntity entity =
        new CoderOauthCredentialEntity(
            accountId,
            tokenResponse.accessToken(),
            refreshToken,
            tokenResponse.tokenType(),
            tokenResponse.scope() == null ? scopes : tokenResponse.scope(),
            expiresAt,
            existing == null ? now : existing.getCreatedAt(),
            now);
    return credentialRepository.save(entity);
  }

  private void tryRevokeRemote(CoderOauthCredentialEntity credential) {
    String token =
        credential.getRefreshToken() != null && !credential.getRefreshToken().isBlank()
            ? credential.getRefreshToken()
            : credential.getAccessToken();
    String revocationEndpoint = getAuthorizationServerMetadata().revocationEndpoint();
    if (token == null
        || token.isBlank()
        || revocationEndpoint == null
        || revocationEndpoint.isBlank()) {
      return;
    }
    try {
      OAuthClientRegistration registration = ensureClientRegistration();
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("token", token);
      form.add("client_id", registration.clientId());
      if (registration.clientSecret() != null && !registration.clientSecret().isBlank()) {
        form.add("client_secret", registration.clientSecret());
      }
      restClient
          .post()
          .uri(revocationEndpoint)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      log.warn("Failed to revoke Coder token remotely; deleting local credential", e);
    }
  }

  private OAuthClientRegistration ensureClientRegistration() {
    AuthorizationServerMetadata metadata = getAuthorizationServerMetadata();
    if (configuredClientId != null && !configuredClientId.isBlank()) {
      return new OAuthClientRegistration(
          metadata.issuer(), configuredClientId, configuredClientSecret, "client_secret_post");
    }
    throw new IllegalStateException("Coder OAuth client id is not configured");
  }

  private ProtectedResourceMetadata getProtectedResourceMetadata() {
    ProtectedResourceMetadata cached = protectedResourceMetadata;
    if (cached != null) {
      return cached;
    }
    URI serverUri = URI.create(serverUrl);
    String metadataUri = serverOrigin(serverUri) + "/.well-known/oauth-protected-resource";
    ProtectedResourceMetadata metadata =
        restClient.get().uri(metadataUri).retrieve().body(ProtectedResourceMetadata.class);
    if (metadata == null || metadata.resource() == null || metadata.resource().isBlank()) {
      metadata = new ProtectedResourceMetadata(serverOrigin(serverUri), List.of(), List.of());
    }
    protectedResourceMetadata = metadata;
    return metadata;
  }

  private AuthorizationServerMetadata getAuthorizationServerMetadata() {
    AuthorizationServerMetadata cached = authorizationServerMetadata;
    if (cached != null) {
      return cached;
    }
    ProtectedResourceMetadata resourceMetadata = getProtectedResourceMetadata();
    String issuer =
        resourceMetadata.authorizationServers() == null
                || resourceMetadata.authorizationServers().isEmpty()
            ? serverOrigin(URI.create(serverUrl))
            : resourceMetadata.authorizationServers().getFirst();
    String metadataUri = issuer + "/.well-known/oauth-authorization-server";
    AuthorizationServerMetadata metadata =
        restClient.get().uri(metadataUri).retrieve().body(AuthorizationServerMetadata.class);
    if (metadata == null) {
      throw new IllegalStateException("Failed to load Coder OAuth authorization metadata");
    }
    authorizationServerMetadata = metadata;
    return metadata;
  }

  private String serverOrigin(URI uri) {
    return uri.getScheme() + "://" + uri.getAuthority();
  }

  public record OauthCompletion(String accountId, String chatGuid, String messageGuid) {}

  public record CoderLinkedAccount(
      String accountId, @Nullable String email, @Nullable String label) {}

  private record OAuthClientRegistration(
      String issuer, String clientId, String clientSecret, String tokenEndpointAuthMethod) {}

  private record ProtectedResourceMetadata(
      String resource,
      @JsonProperty("authorization_servers") List<String> authorizationServers,
      @JsonProperty("scopes_supported") List<String> scopesSupported) {}

  private record AuthorizationServerMetadata(
      String issuer,
      @JsonProperty("authorization_endpoint") String authorizationEndpoint,
      @JsonProperty("token_endpoint") String tokenEndpoint,
      @JsonProperty("registration_endpoint") String registrationEndpoint,
      @JsonProperty("revocation_endpoint") String revocationEndpoint,
      @JsonProperty("scopes_supported") List<String> scopesSupported) {}

  private record CoderUserProfile(
      @JsonProperty("email") String email,
      @JsonProperty("username") String username,
      @JsonProperty("name") String name) {}

  private record TokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("refresh_token") String refreshToken,
      @JsonProperty("token_type") String tokenType,
      @JsonProperty("expires_in") Long expiresIn,
      String scope) {}
}

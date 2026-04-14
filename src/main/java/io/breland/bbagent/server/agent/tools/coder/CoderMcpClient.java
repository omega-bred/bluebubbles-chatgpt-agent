package io.breland.bbagent.server.agent.tools.coder;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthClientEntity;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthClientRepository;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthCredentialEntity;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthCredentialRepository;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthPendingAuthorizationEntity;
import io.breland.bbagent.server.agent.persistence.coder.CoderOauthPendingAuthorizationRepository;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpHttpClientAuthorizationErrorHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
public class CoderMcpClient {
  public static final String TOOL_PREFIX = "coder__";

  private static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(10);
  private static final Duration TOKEN_REFRESH_LEEWAY = Duration.ofMinutes(1);
  private static final int MAX_TOOL_NAME_LENGTH = 64;
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ObjectMapper objectMapper;
  private final RestClient restClient;
  private final CoderOauthClientRepository clientRepository;
  private final CoderOauthCredentialRepository credentialRepository;
  private final CoderOauthPendingAuthorizationRepository pendingAuthorizationRepository;
  private final String serverUrl;
  private final String redirectUri;
  private final String configuredClientId;
  private final String configuredClientSecret;
  private final String scopes;
  private final String clientName;
  private final Duration requestTimeout;
  private final Duration toolCacheTtl;
  private final Algorithm stateAlgorithm;
  private final SecureRandom secureRandom = new SecureRandom();
  private final Map<String, CachedTools> toolsCache = new ConcurrentHashMap<>();

  private volatile ProtectedResourceMetadata protectedResourceMetadata;
  private volatile AuthorizationServerMetadata authorizationServerMetadata;

  public CoderMcpClient(
      @Value("${coder.mcp.server_url:}") String serverUrl,
      @Value("${coder.oauth.redirect_uri:}") String redirectUri,
      @Value("${coder.oauth.state_secret:}") String stateSecret,
      @Value("${coder.oauth.client_id:}") String configuredClientId,
      @Value("${coder.oauth.client_secret:}") String configuredClientSecret,
      @Value("${coder.oauth.scopes:coder:all}") String scopes,
      @Value("${coder.oauth.client_name:BlueBubbles ChatGPT Agent}") String clientName,
      @Value("${coder.mcp.request_timeout_seconds:30}") long requestTimeoutSeconds,
      @Value("${coder.mcp.tool_cache_ttl_seconds:300}") long toolCacheTtlSeconds,
      RestClient.Builder restClientBuilder,
      CoderOauthClientRepository clientRepository,
      CoderOauthCredentialRepository credentialRepository,
      CoderOauthPendingAuthorizationRepository pendingAuthorizationRepository,
      ObjectMapper objectMapper) {
    this.serverUrl = serverUrl;
    this.redirectUri = redirectUri;
    this.stateAlgorithm =
        stateSecret == null || stateSecret.isBlank() ? null : Algorithm.HMAC256(stateSecret);
    this.configuredClientId = configuredClientId;
    this.configuredClientSecret = configuredClientSecret;
    this.scopes = scopes;
    this.clientName = clientName;
    this.requestTimeout = Duration.ofSeconds(Math.max(1, requestTimeoutSeconds));
    this.toolCacheTtl = Duration.ofSeconds(Math.max(1, toolCacheTtlSeconds));
    this.restClient = restClientBuilder.build();
    this.clientRepository = clientRepository;
    this.credentialRepository = credentialRepository;
    this.pendingAuthorizationRepository = pendingAuthorizationRepository;
    this.objectMapper = objectMapper;
  }

  public boolean isConfigured() {
    return serverUrl != null
        && !serverUrl.isBlank()
        && redirectUri != null
        && !redirectUri.isBlank()
        && stateAlgorithm != null;
  }

  public boolean isLinked(String accountBase) {
    return resolveLinkedAccountBase(accountBase).isPresent();
  }

  public Optional<String> getAuthUrl(String accountBase, String chatGuid, String messageGuid) {
    if (!isConfigured() || accountBase == null || accountBase.isBlank()) {
      return Optional.empty();
    }
    try {
      pendingAuthorizationRepository.deleteByExpiresAtBefore(Instant.now());
      OAuthClientRegistration registration = ensureClientRegistration();
      AuthorizationServerMetadata authMetadata = getAuthorizationServerMetadata();
      ProtectedResourceMetadata resourceMetadata = getProtectedResourceMetadata();
      String pendingId = UUID.randomUUID().toString();
      String codeVerifier = generateCodeVerifier();
      String state = createOauthState(accountBase, pendingId, chatGuid, messageGuid);
      if (state == null || state.isBlank()) {
        return Optional.empty();
      }
      Instant now = Instant.now();
      pendingAuthorizationRepository.save(
          new CoderOauthPendingAuthorizationEntity(
              pendingId,
              accountBase,
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
              .queryParam("state", state)
              .queryParam("code_challenge", codeChallenge(codeVerifier))
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
    Optional<OauthState> parsedState = parseOauthState(state);
    if (parsedState.isEmpty()) {
      return Optional.empty();
    }
    OauthState oauthState = parsedState.get();
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
    if (!pendingAuth.getAccountBase().equals(oauthState.accountBase())) {
      return Optional.empty();
    }
    try {
      TokenResponse tokenResponse =
          requestAuthorizationCodeToken(code, pendingAuth.getCodeVerifier());
      saveCredential(oauthState.accountBase(), tokenResponse);
      pendingAuthorizationRepository.deleteById(pendingAuth.getPendingId());
      toolsCache.remove(oauthState.accountBase());
      return Optional.of(
          new OauthCompletion(
              oauthState.accountBase(), oauthState.chatGuid(), oauthState.messageGuid()));
    } catch (Exception e) {
      log.warn("Failed to complete Coder OAuth", e);
      return Optional.empty();
    }
  }

  public boolean revoke(String accountBase) {
    Optional<String> linkedAccountBase = resolveLinkedAccountBase(accountBase);
    if (linkedAccountBase.isEmpty()) {
      return false;
    }
    String credentialAccountBase = linkedAccountBase.get();
    Optional<CoderOauthCredentialEntity> existing =
        credentialRepository.findById(credentialAccountBase);
    existing.ifPresent(this::tryRevokeRemote);
    credentialRepository.deleteById(credentialAccountBase);
    toolsCache.remove(credentialAccountBase);
    return existing.isPresent();
  }

  public List<AgentTool> getAgentTools(String accountBase) {
    if (!isConfigured()) {
      return List.of();
    }
    Optional<String> linkedAccountBase = resolveLinkedAccountBase(accountBase);
    if (linkedAccountBase.isEmpty()) {
      return List.of();
    }
    try {
      String credentialAccountBase = linkedAccountBase.get();
      List<CoderToolDefinition> definitions = getToolDefinitions(credentialAccountBase);
      List<AgentTool> result = new ArrayList<>();
      for (CoderToolDefinition definition : definitions) {
        result.add(toAgentTool(accountBase, credentialAccountBase, definition));
      }
      return result;
    } catch (Exception e) {
      log.warn("Failed to load Coder MCP tools", e);
      return List.of();
    }
  }

  public Optional<AgentTool> getAgentTool(String accountBase, String agentToolName) {
    if (agentToolName == null || !agentToolName.startsWith(TOOL_PREFIX)) {
      return Optional.empty();
    }
    return getAgentTools(accountBase).stream()
        .filter(tool -> agentToolName.equals(tool.name()))
        .findFirst();
  }

  private AgentTool toAgentTool(
      String requestedAccountBase, String credentialAccountBase, CoderToolDefinition definition) {
    return new AgentTool(
        definition.agentName(),
        toolDescription(definition),
        parameters(definition.tool().inputSchema()),
        false,
        (context, args) -> {
          try {
            String resolvedAccountBase = resolveAccountBase(context);
            if (resolvedAccountBase == null || !resolvedAccountBase.equals(requestedAccountBase)) {
              return "Coder account mismatch";
            }
            return callTool(credentialAccountBase, definition.agentName(), args);
          } catch (Exception e) {
            log.warn("Coder MCP tool call failed: {}", definition.mcpName(), e);
            return "Coder MCP tool call failed: " + e.getMessage();
          }
        });
  }

  private String callTool(String accountBase, String agentToolName, JsonNode args)
      throws IOException {
    CoderToolDefinition definition =
        getToolDefinitions(accountBase).stream()
            .filter(tool -> agentToolName.equals(tool.agentName()))
            .findFirst()
            .orElseThrow(() -> new IOException("Unknown Coder MCP tool: " + agentToolName));
    Map<String, Object> arguments =
        args == null || args.isNull() ? Map.of() : objectMapper.convertValue(args, MAP_TYPE);
    McpSchema.CallToolResult result =
        withClient(
            accountBase,
            client ->
                client.callTool(new McpSchema.CallToolRequest(definition.mcpName(), arguments)));
    return formatToolResult(result);
  }

  private List<CoderToolDefinition> getToolDefinitions(String accountBase) throws IOException {
    CachedTools cached = toolsCache.get(accountBase);
    if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
      return cached.tools();
    }
    List<McpSchema.Tool> mcpTools =
        withClient(
            accountBase,
            client -> {
              List<McpSchema.Tool> allTools = new ArrayList<>();
              String cursor = null;
              do {
                McpSchema.ListToolsResult page =
                    cursor == null ? client.listTools() : client.listTools(cursor);
                if (page != null && page.tools() != null) {
                  allTools.addAll(page.tools());
                }
                cursor = page == null ? null : page.nextCursor();
              } while (cursor != null && !cursor.isBlank());
              return allTools;
            });
    List<CoderToolDefinition> definitions = new ArrayList<>();
    if (mcpTools != null) {
      Map<String, Integer> nameCounts = new LinkedHashMap<>();
      for (McpSchema.Tool tool : mcpTools) {
        String agentName = toAgentToolName(tool.name());
        int count = nameCounts.merge(agentName, 1, Integer::sum);
        if (count > 1) {
          agentName = disambiguateAgentToolName(tool.name(), count);
        }
        definitions.add(new CoderToolDefinition(agentName, tool.name(), tool));
      }
    }
    CachedTools next = new CachedTools(List.copyOf(definitions), Instant.now().plus(toolCacheTtl));
    toolsCache.put(accountBase, next);
    return next.tools();
  }

  private <T> T withClient(String accountBase, Function<McpSyncClient, T> callback)
      throws IOException {
    if (!isLinked(accountBase)) {
      throw new IOException("Coder account is not linked");
    }
    ParsedMcpServer parsedServer = parseServerUrl();
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder(parsedServer.baseUri())
            .endpoint(parsedServer.endpoint())
            .httpRequestCustomizer(
                (builder, method, endpoint, body, context) ->
                    builder.header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + getValidAccessTokenUnchecked(accountBase)))
            .authorizationErrorHandler(
                McpHttpClientAuthorizationErrorHandler.fromSync(
                    (responseInfo, context) -> refreshCredential(accountBase, true).isPresent()))
            .connectTimeout(requestTimeout)
            .build();
    McpSyncClient client =
        McpClient.sync(transport)
            .requestTimeout(requestTimeout)
            .initializationTimeout(requestTimeout)
            .clientInfo(new McpSchema.Implementation("BlueBubbles ChatGPT Agent", "0.0.1"))
            .build();
    try {
      client.initialize();
      return callback.apply(client);
    } catch (McpError e) {
      throw new IOException("Coder MCP request failed: " + throwableMessage(e), e);
    } catch (RuntimeException e) {
      throw toCoderMcpIOException(e);
    } finally {
      client.closeGracefully();
    }
  }

  private IOException toCoderMcpIOException(RuntimeException e) {
    Throwable current = e;
    while (current != null) {
      if (current instanceof McpError) {
        return new IOException("Coder MCP request failed: " + throwableMessage(current), current);
      }
      current = current.getCause();
    }
    return new IOException("Coder MCP request failed: " + throwableMessage(e), e);
  }

  private static String throwableMessage(Throwable throwable) {
    String message = throwable.getMessage();
    return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
  }

  private String getValidAccessToken(String accountBase) throws IOException {
    CoderOauthCredentialEntity credential =
        credentialRepository
            .findById(accountBase)
            .orElseThrow(() -> new IOException("Coder account is not linked"));
    if (!isExpired(credential)) {
      return credential.getAccessToken();
    }
    return refreshCredential(accountBase, false)
        .map(CoderOauthCredentialEntity::getAccessToken)
        .orElseThrow(() -> new IOException("Coder token expired; please relink Coder"));
  }

  private Optional<String> resolveLinkedAccountBase(String accountBase) {
    if (accountBase == null || accountBase.isBlank()) {
      return Optional.empty();
    }
    if (credentialRepository.existsById(accountBase)) {
      return Optional.of(accountBase);
    }
    return credentialRepository
        .findFirstByAccountBaseEndingWithOrderByUpdatedAtDesc("|" + accountBase)
        .map(CoderOauthCredentialEntity::getAccountBase);
  }

  private String getValidAccessTokenUnchecked(String accountBase) {
    try {
      return getValidAccessToken(accountBase);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Optional<CoderOauthCredentialEntity> refreshCredential(
      String accountBase, boolean forceRefresh) {
    Optional<CoderOauthCredentialEntity> existing = credentialRepository.findById(accountBase);
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    CoderOauthCredentialEntity credential = existing.get();
    if (!forceRefresh && !isExpired(credential)) {
      return Optional.of(credential);
    }
    if (credential.getRefreshToken() == null || credential.getRefreshToken().isBlank()) {
      return Optional.empty();
    }
    try {
      TokenResponse tokenResponse = requestRefreshToken(credential.getRefreshToken());
      CoderOauthCredentialEntity saved = saveCredential(accountBase, tokenResponse);
      toolsCache.remove(accountBase);
      return Optional.of(saved);
    } catch (Exception e) {
      log.warn("Failed to refresh Coder OAuth token", e);
      return Optional.empty();
    }
  }

  private boolean isExpired(CoderOauthCredentialEntity credential) {
    if (credential.getAccessToken() == null || credential.getAccessToken().isBlank()) {
      return true;
    }
    Instant expiresAt = credential.getExpiresAt();
    return expiresAt != null && expiresAt.minus(TOKEN_REFRESH_LEEWAY).isBefore(Instant.now());
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

  private CoderOauthCredentialEntity saveCredential(
      String accountBase, TokenResponse tokenResponse) {
    if (tokenResponse == null
        || tokenResponse.accessToken() == null
        || tokenResponse.accessToken().isBlank()) {
      throw new IllegalArgumentException("Token response did not include an access token");
    }
    Instant now = Instant.now();
    CoderOauthCredentialEntity existing = credentialRepository.findById(accountBase).orElse(null);
    String refreshToken = tokenResponse.refreshToken();
    if ((refreshToken == null || refreshToken.isBlank()) && existing != null) {
      refreshToken = existing.getRefreshToken();
    }
    Instant expiresAt =
        tokenResponse.expiresIn() == null ? null : now.plusSeconds(tokenResponse.expiresIn());
    CoderOauthCredentialEntity entity =
        new CoderOauthCredentialEntity(
            accountBase,
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
    Optional<CoderOauthClientEntity> existing = clientRepository.findById(metadata.issuer());
    if (existing.isPresent() && redirectUri.equals(existing.get().getRedirectUri())) {
      CoderOauthClientEntity entity = existing.get();
      return new OAuthClientRegistration(
          entity.getIssuer(),
          entity.getClientId(),
          entity.getClientSecret(),
          entity.getTokenEndpointAuthMethod());
    }
    if (metadata.registrationEndpoint() == null || metadata.registrationEndpoint().isBlank()) {
      throw new IllegalStateException("Coder OAuth dynamic registration endpoint is unavailable");
    }
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("client_name", clientName);
    request.put("redirect_uris", List.of(redirectUri));
    request.put("grant_types", List.of("authorization_code", "refresh_token"));
    request.put("response_types", List.of("code"));
    request.put("scope", scopes);
    request.put("token_endpoint_auth_method", "client_secret_post");
    RegistrationResponse response =
        restClient
            .post()
            .uri(metadata.registrationEndpoint())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(RegistrationResponse.class);
    if (response == null || response.clientId() == null || response.clientId().isBlank()) {
      throw new IllegalStateException("Coder OAuth registration failed");
    }
    Instant now = Instant.now();
    CoderOauthClientEntity entity =
        clientRepository.save(
            new CoderOauthClientEntity(
                metadata.issuer(),
                response.clientId(),
                response.clientSecret(),
                redirectUri,
                response.tokenEndpointAuthMethod(),
                now,
                now));
    return new OAuthClientRegistration(
        entity.getIssuer(),
        entity.getClientId(),
        entity.getClientSecret(),
        entity.getTokenEndpointAuthMethod());
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
            : resourceMetadata.authorizationServers().get(0);
    String metadataUri = issuer + "/.well-known/oauth-authorization-server";
    AuthorizationServerMetadata metadata =
        restClient.get().uri(metadataUri).retrieve().body(AuthorizationServerMetadata.class);
    if (metadata == null) {
      throw new IllegalStateException("Failed to load Coder OAuth authorization metadata");
    }
    authorizationServerMetadata = metadata;
    return metadata;
  }

  private ParsedMcpServer parseServerUrl() {
    URI uri = URI.create(serverUrl);
    String baseUri = serverOrigin(uri);
    String endpoint = uri.getRawPath();
    if (endpoint == null || endpoint.isBlank()) {
      endpoint = "/mcp";
    }
    if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
      endpoint += "?" + uri.getRawQuery();
    }
    return new ParsedMcpServer(baseUri, endpoint);
  }

  private String serverOrigin(URI uri) {
    return uri.getScheme() + "://" + uri.getAuthority();
  }

  private String createOauthState(
      String accountBase, String pendingId, String chatGuid, String messageGuid) {
    if (stateAlgorithm == null) {
      return null;
    }
    try {
      Instant now = Instant.now();
      return JWT.create()
          .withIssuedAt(Date.from(now))
          .withExpiresAt(Date.from(now.plus(OAUTH_STATE_TTL)))
          .withClaim("account_base", accountBase)
          .withClaim("pending_id", pendingId)
          .withClaim("chat_guid", chatGuid)
          .withClaim("message_guid", messageGuid)
          .sign(stateAlgorithm);
    } catch (Exception e) {
      log.warn("Failed to build Coder OAuth state", e);
      return null;
    }
  }

  private Optional<OauthState> parseOauthState(String state) {
    if (state == null || state.isBlank() || stateAlgorithm == null) {
      return Optional.empty();
    }
    try {
      JWTVerifier verifier = JWT.require(stateAlgorithm).build();
      DecodedJWT jwt = verifier.verify(state);
      String accountBase = jwt.getClaim("account_base").asString();
      String pendingId = jwt.getClaim("pending_id").asString();
      String chatGuid = jwt.getClaim("chat_guid").asString();
      String messageGuid = jwt.getClaim("message_guid").asString();
      if (accountBase == null
          || accountBase.isBlank()
          || pendingId == null
          || pendingId.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(new OauthState(accountBase, pendingId, chatGuid, messageGuid));
    } catch (JWTVerificationException e) {
      log.warn("Failed to parse Coder OAuth state", e);
      return Optional.empty();
    }
  }

  private String generateCodeVerifier() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String codeChallenge(String verifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create PKCE code challenge", e);
    }
  }

  private String toAgentToolName(String mcpToolName) {
    String normalized =
        mcpToolName == null
            ? "tool"
            : mcpToolName.replaceAll("[^A-Za-z0-9_-]", "_").replaceAll("_+", "_");
    if (normalized.isBlank()) {
      normalized = "tool";
    }
    String hash = shortHash(mcpToolName);
    int maxBaseLength = MAX_TOOL_NAME_LENGTH - TOOL_PREFIX.length() - hash.length() - 1;
    String truncated =
        normalized.length() > maxBaseLength ? normalized.substring(0, maxBaseLength) : normalized;
    return TOOL_PREFIX + truncated + "_" + hash;
  }

  private String disambiguateAgentToolName(String mcpToolName, int count) {
    String suffix = "_" + count;
    String base = toAgentToolName(mcpToolName);
    if (base.length() + suffix.length() <= MAX_TOOL_NAME_LENGTH) {
      return base + suffix;
    }
    return base.substring(0, MAX_TOOL_NAME_LENGTH - suffix.length()) + suffix;
  }

  private String shortHash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < 6; i++) {
        builder.append(String.format(Locale.ROOT, "%02x", hashed[i]));
      }
      return builder.toString();
    } catch (Exception e) {
      return Integer.toHexString(String.valueOf(value).hashCode());
    }
  }

  private String toolDescription(CoderToolDefinition definition) {
    String description = definition.tool().description();
    if (description == null || description.isBlank()) {
      description = definition.tool().title();
    }
    if (description == null || description.isBlank()) {
      description = "Call a Coder MCP tool.";
    }
    if ("coder_create_task".equals(definition.mcpName())) {
      description +=
          " For template_version_id, pass a valid template version UUID from Coder, not a template"
              + " name, display name, or template ID.";
    }
    String full = "Coder MCP tool `" + definition.mcpName() + "`: " + description;
    return full.length() > 900 ? full.substring(0, 897) + "..." : full;
  }

  private FunctionTool.Parameters parameters(McpSchema.JsonSchema schema) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    if (schema != null) {
      if (schema.type() != null) {
        normalized.put("type", schema.type());
      }
      if (schema.properties() != null) {
        normalized.put("properties", schema.properties());
      }
      if (schema.required() != null) {
        normalized.put("required", schema.required());
      }
      if (schema.additionalProperties() != null) {
        normalized.put("additionalProperties", schema.additionalProperties());
      }
      if (schema.defs() != null) {
        normalized.put("$defs", schema.defs());
      }
      if (schema.definitions() != null) {
        normalized.put("definitions", schema.definitions());
      }
    }
    normalized.putIfAbsent("type", "object");
    normalized.putIfAbsent("properties", new LinkedHashMap<>());
    FunctionTool.Parameters.Builder builder = FunctionTool.Parameters.builder();
    for (Map.Entry<String, Object> entry : normalized.entrySet()) {
      builder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
    }
    return builder.build();
  }

  private String formatToolResult(McpSchema.CallToolResult result) throws JsonProcessingException {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("is_error", result != null && Boolean.TRUE.equals(result.isError()));
    if (result != null && result.structuredContent() != null) {
      response.put("structured_content", result.structuredContent());
    }
    List<Map<String, Object>> content = new ArrayList<>();
    if (result != null && result.content() != null) {
      for (McpSchema.Content item : result.content()) {
        content.add(contentToMap(item));
      }
    }
    response.put("content", content);
    return objectMapper.writeValueAsString(response);
  }

  private Map<String, Object> contentToMap(McpSchema.Content content) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("type", content.type());
    if (content instanceof McpSchema.TextContent textContent) {
      item.put("text", textContent.text());
    } else if (content instanceof McpSchema.ImageContent imageContent) {
      item.put("mime_type", imageContent.mimeType());
      item.put("data", "[base64 image data omitted]");
    } else if (content instanceof McpSchema.AudioContent audioContent) {
      item.put("mime_type", audioContent.mimeType());
      item.put("data", "[base64 audio data omitted]");
    } else {
      item.put("value", content.toString());
    }
    return item;
  }

  public static String resolveAccountBase(
      io.breland.bbagent.server.agent.tools.ToolContext context) {
    if (context == null || context.message() == null) {
      return null;
    }
    return resolveAccountBase(context.message());
  }

  public static String resolveAccountBase(io.breland.bbagent.server.agent.IncomingMessage message) {
    if (message == null) {
      return null;
    }
    String chatGuid = message.chatGuid();
    String sender = message.sender();
    if (sender != null && !sender.isBlank()) {
      return sender;
    }
    if (chatGuid != null && !chatGuid.isBlank()) {
      return chatGuid;
    }
    return null;
  }

  public record OauthCompletion(String accountBase, String chatGuid, String messageGuid) {}

  private record OauthState(
      String accountBase, String pendingId, String chatGuid, String messageGuid) {}

  private record OAuthClientRegistration(
      String issuer, String clientId, String clientSecret, String tokenEndpointAuthMethod) {}

  private record ParsedMcpServer(String baseUri, String endpoint) {}

  private record CachedTools(List<CoderToolDefinition> tools, Instant expiresAt) {}

  private record CoderToolDefinition(String agentName, String mcpName, McpSchema.Tool tool) {}

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

  private record RegistrationResponse(
      @JsonProperty("client_id") String clientId,
      @JsonProperty("client_secret") String clientSecret,
      @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod) {}

  private record TokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("refresh_token") String refreshToken,
      @JsonProperty("token_type") String tokenType,
      @JsonProperty("expires_in") Long expiresIn,
      String scope) {}
}

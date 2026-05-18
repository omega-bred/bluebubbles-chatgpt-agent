package io.breland.bbagent.server.agent.tools.coder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
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
  public static final String TOOL_PREFIX = "coder__";

  private static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(10);
  private static final Duration TOKEN_REFRESH_LEEWAY = Duration.ofMinutes(1);
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ObjectMapper objectMapper;
  private final CoderToolNameMapper toolNameMapper = new CoderToolNameMapper();
  private final CoderToolResultFormatter toolResultFormatter;
  private final RestClient restClient;
  private final CoderOauthCredentialRepository credentialRepository;
  private final CoderOauthPendingAuthorizationRepository pendingAuthorizationRepository;
  private final String serverUrl;
  private final String redirectUri;
  private final String configuredClientId;
  private final String configuredClientSecret;
  private final String scopes;
  private final Duration requestTimeout;
  private final Duration toolCacheTtl;
  private final CoderOauthStateCodec stateCodec;
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
      @Value("${coder.mcp.request_timeout_seconds:30}") long requestTimeoutSeconds,
      @Value("${coder.mcp.tool_cache_ttl_seconds:300}") long toolCacheTtlSeconds,
      RestClient.Builder restClientBuilder,
      CoderOauthCredentialRepository credentialRepository,
      CoderOauthPendingAuthorizationRepository pendingAuthorizationRepository,
      ObjectMapper objectMapper) {
    this.serverUrl = serverUrl;
    this.redirectUri = redirectUri;
    this.stateCodec = new CoderOauthStateCodec(stateSecret, OAUTH_STATE_TTL);
    this.configuredClientId = configuredClientId;
    this.configuredClientSecret = configuredClientSecret;
    this.scopes = scopes;
    this.requestTimeout = Duration.ofSeconds(Math.max(1, requestTimeoutSeconds));
    this.toolCacheTtl = Duration.ofSeconds(Math.max(1, toolCacheTtlSeconds));
    this.restClient = restClientBuilder.build();
    this.credentialRepository = credentialRepository;
    this.pendingAuthorizationRepository = pendingAuthorizationRepository;
    this.objectMapper = objectMapper;
    this.toolResultFormatter = new CoderToolResultFormatter(objectMapper);
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
      toolsCache.remove(oauthState.accountId());
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
    toolsCache.remove(credentialAccountId);
    return existing.isPresent();
  }

  public List<AgentTool> getAgentTools(String accountId) {
    return getAgentTools(accountId, java.util.Set.of());
  }

  public List<AgentTool> getAgentTools(String accountId, java.util.Set<String> excludedMcpNames) {
    if (!isConfigured()) {
      return List.of();
    }
    Optional<String> linkedAccountId = resolveLinkedAccountId(accountId);
    if (linkedAccountId.isEmpty()) {
      return List.of();
    }
    try {
      String credentialAccountId = linkedAccountId.get();
      List<CoderToolDefinition> definitions = getToolDefinitions(credentialAccountId);
      List<AgentTool> result = new ArrayList<>();
      for (CoderToolDefinition definition : definitions) {
        if (excludedMcpNames != null && excludedMcpNames.contains(definition.mcpName())) {
          continue;
        }
        result.add(toAgentTool(accountId, credentialAccountId, definition));
      }
      return result;
    } catch (Exception e) {
      log.warn("Failed to load Coder MCP tools", e);
      return List.of();
    }
  }

  public Optional<AgentTool> getAgentTool(String accountId, String agentToolName) {
    return getAgentTool(accountId, agentToolName, java.util.Set.of());
  }

  public Optional<AgentTool> getAgentTool(
      String accountId, String agentToolName, java.util.Set<String> excludedMcpNames) {
    if (agentToolName == null || !agentToolName.startsWith(TOOL_PREFIX)) {
      return Optional.empty();
    }
    return getAgentTools(accountId, excludedMcpNames).stream()
        .filter(tool -> agentToolName.equals(tool.name()))
        .findFirst();
  }

  public String callMcpTool(String accountId, String mcpToolName, Map<String, Object> arguments)
      throws IOException {
    if (mcpToolName == null || mcpToolName.isBlank()) {
      throw new IOException("missing Coder MCP tool name");
    }
    Optional<String> linkedAccountId = resolveLinkedAccountId(accountId);
    if (linkedAccountId.isEmpty()) {
      throw new IOException("Coder account is not linked");
    }
    String credentialAccountId = linkedAccountId.get();
    CoderToolDefinition definition =
        getToolDefinitions(credentialAccountId).stream()
            .filter(tool -> mcpToolName.equals(tool.mcpName()))
            .findFirst()
            .orElseThrow(() -> new IOException("Unknown Coder MCP tool: " + mcpToolName));
    McpSchema.CallToolResult result =
        withClient(
            credentialAccountId,
            client ->
                client.callTool(
                    new McpSchema.CallToolRequest(
                        definition.mcpName(),
                        arguments == null ? Map.of() : Map.copyOf(arguments))));
    return toolResultFormatter.format(result);
  }

  private AgentTool toAgentTool(
      String requestedAccountId, String credentialAccountId, CoderToolDefinition definition) {
    return new AgentTool(
        definition.agentName(),
        toolDescription(definition),
        parameters(definition.tool().inputSchema()),
        false,
        (context, args) -> {
          try {
            String resolvedAccountId = context.accountId();
            if (resolvedAccountId == null || !resolvedAccountId.equals(requestedAccountId)) {
              return "Coder account mismatch";
            }
            return callTool(credentialAccountId, definition.agentName(), args);
          } catch (Exception e) {
            log.warn("Coder MCP tool call failed: {}", definition.mcpName(), e);
            return "Coder MCP tool call failed: " + e.getMessage();
          }
        });
  }

  private String callTool(String accountId, String agentToolName, JsonNode args)
      throws IOException {
    CoderToolDefinition definition =
        getToolDefinitions(accountId).stream()
            .filter(tool -> agentToolName.equals(tool.agentName()))
            .findFirst()
            .orElseThrow(() -> new IOException("Unknown Coder MCP tool: " + agentToolName));
    Map<String, Object> arguments =
        args == null || args.isNull() ? Map.of() : objectMapper.convertValue(args, MAP_TYPE);
    McpSchema.CallToolResult result =
        withClient(
            accountId,
            client ->
                client.callTool(new McpSchema.CallToolRequest(definition.mcpName(), arguments)));
    return toolResultFormatter.format(result);
  }

  private List<CoderToolDefinition> getToolDefinitions(String accountId) throws IOException {
    CachedTools cached = toolsCache.get(accountId);
    if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
      return cached.tools();
    }
    List<McpSchema.Tool> mcpTools =
        withClient(
            accountId,
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
        String agentName = toolNameMapper.toAgentToolName(tool.name());
        int count = nameCounts.merge(agentName, 1, Integer::sum);
        if (count > 1) {
          agentName = toolNameMapper.disambiguateAgentToolName(tool.name(), count);
        }
        definitions.add(new CoderToolDefinition(agentName, tool.name(), tool));
      }
    }
    CachedTools next = new CachedTools(List.copyOf(definitions), Instant.now().plus(toolCacheTtl));
    toolsCache.put(accountId, next);
    return next.tools();
  }

  private <T> T withClient(String accountId, Function<McpSyncClient, T> callback)
      throws IOException {
    if (!isLinked(accountId)) {
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
                        "Bearer " + getValidAccessTokenUnchecked(accountId)))
            .authorizationErrorHandler(
                McpHttpClientAuthorizationErrorHandler.fromSync(
                    (responseInfo, context) -> refreshCredential(accountId, true).isPresent()))
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

  private String getValidAccessToken(String accountId) throws IOException {
    CoderOauthCredentialEntity credential =
        credentialRepository
            .findById(accountId)
            .orElseThrow(() -> new IOException("Coder account is not linked"));
    if (!isExpired(credential)) {
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

  public Optional<String> findLinkedAccountId(String accountId) {
    return resolveLinkedAccountId(accountId);
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
    if (!forceRefresh && !isExpired(credential)) {
      return Optional.of(credential);
    }
    if (credential.getRefreshToken() == null || credential.getRefreshToken().isBlank()) {
      return Optional.empty();
    }
    try {
      TokenResponse tokenResponse = requestRefreshToken(credential.getRefreshToken());
      CoderOauthCredentialEntity saved = saveCredential(accountId, tokenResponse);
      toolsCache.remove(accountId);
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

  public record OauthCompletion(String accountId, String chatGuid, String messageGuid) {}

  public record CoderLinkedAccount(
      String accountId, @Nullable String email, @Nullable String label) {}

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

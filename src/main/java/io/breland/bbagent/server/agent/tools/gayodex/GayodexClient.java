package io.breland.bbagent.server.agent.tools.gayodex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.config.Jackson2WebClientConfigurer;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Dedicated, narrow REST client. No owner OAuth/MCP paths and no caller-provided headers/URLs. */
@Component
public class GayodexClient {
  private final GayodexProperties properties;
  private final WebClient http;

  public GayodexClient(GayodexProperties properties, ObjectMapper mapper) {
    this.properties = properties;
    this.http =
        Jackson2WebClientConfigurer.configure(WebClient.builder(), mapper)
            .clientConnector(
                new ReactorClientHttpConnector(
                    reactor.netty.http.client.HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                        .responseTimeout(Duration.ofSeconds(15))
                        .disableRetry(true)
                        .followRedirect(false)))
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(32 * 1024))
            .build();
  }

  public boolean isAllowed(IncomingMessage message) {
    return properties.isConfigured() && GayodexInboundActor.verifiedPhone(message).isPresent();
  }

  JsonNode request(IncomingMessage message, Operation operation, JsonNode body, String jobId) {
    if (!isAllowed(message))
      throw new Failure(
          "unavailable_actor",
          "Gayodex requires a verified incoming BlueChat phone message and configured service.");
    String path =
        switch (operation) {
          case READ, CREATE, UPDATE -> "/api/v1/bluechat/profile";
          case PHOTO -> "/api/v1/bluechat/photos";
          case JOB -> {
            if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{1,128}"))
              throw new Failure("invalid_request", "Use the photo job ID returned for your entry.");
            yield "/api/v1/bluechat/photo-jobs/" + jobId;
          }
        };
    HttpMethod method =
        switch (operation) {
          case READ, JOB -> HttpMethod.GET;
          case CREATE, PHOTO -> HttpMethod.POST;
          case UPDATE -> HttpMethod.PATCH;
        };
    try {
      var request =
          http.method(method)
              .uri(properties.getBaseUrl().replaceAll("/$", "") + path)
              .headers(
                  headers -> {
                    headers.setBearerAuth(properties.getToken());
                    headers.set(
                        "X-Gayodex-Sender-Phone",
                        GayodexInboundActor.verifiedPhone(message).orElseThrow());
                  })
              .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
      WebClient.RequestHeadersSpec<?> spec = body == null ? request : request.bodyValue(body);
      return spec.exchangeToMono(
              response -> {
                if (!response.statusCode().is2xxSuccessful())
                  return response
                      .releaseBody()
                      .then(Mono.error(statusFailure(response.statusCode().value())));
                return response.bodyToMono(JsonNode.class);
              })
          .block(Duration.ofSeconds(20));
    } catch (Failure failure) {
      throw failure;
    } catch (Exception ignored) {
      throw new Failure(
          "unavailable",
          "Gayodex could not confirm the result. Read your entry before attempting another write; do not assume it failed or retry blindly.");
    }
  }

  private static Failure statusFailure(int status) {
    return switch (status) {
      case 400, 413, 415 ->
          new Failure(
              "invalid_request",
              "Gayodex rejected the fields or photo. Check the public field and image limits before trying again.");
      case 401, 403 ->
          new Failure(
              "service_access_denied",
              "Gayodex service access is unavailable. Ask the operator to check configuration; no login or account link is needed.");
      case 404 ->
          new Failure(
              "not_found",
              "Your entry or photo job was not found. Read your entry before deciding what to do next.");
      case 409 ->
          new Failure(
              "revision_conflict",
              "Your entry changed or already exists. Read it again and reconsider the requested edit. Never blindly retry or create a duplicate.");
      case 410 ->
          new Failure(
              "owner_removed",
              "The owner removed your entry. Contact the owner to restore access; do not recreate it.");
      case 429 ->
          new Failure(
              "rate_limited",
              "Portrait conversion is limited. Wait before requesting another conversion; do not automatically retry.");
      default ->
          new Failure(
              "unavailable",
              "Gayodex could not confirm the result. Read your entry before another write; do not automatically retry.");
    };
  }

  enum Operation {
    READ,
    CREATE,
    UPDATE,
    PHOTO,
    JOB
  }

  static final class Failure extends RuntimeException {
    final String code;

    Failure(String code, String message) {
      super(message);
      this.code = code;
    }
  }
}

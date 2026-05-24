package io.breland.bbagent.server.linear;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@Slf4j
public class LinearIssueService {
  private static final String ROUTING_QUERY =
      """
      query LinearRouting {
        teams(first: 100) {
          nodes {
            id
            key
            name
          }
        }
        issueLabels(first: 250) {
          nodes {
            id
            name
            team {
              id
              key
              name
            }
          }
        }
      }
      """;
  private static final String ISSUE_CREATE_MUTATION =
      """
      mutation IssueCreate($input: IssueCreateInput!) {
        issueCreate(input: $input) {
          success
          issue {
            id
            identifier
            title
            url
            createdAt
          }
        }
      }
      """;
  private static final String ISSUE_LABEL_CREATE_MUTATION =
      """
      mutation IssueLabelCreate($input: IssueLabelCreateInput!) {
        issueLabelCreate(input: $input) {
          success
          issueLabel {
            id
            name
          }
        }
      }
      """;

  private final LinearProperties properties;
  private final WebClient webClient;
  private final ObjectMapper mapper;

  public LinearIssueService(
      LinearProperties properties, WebClient.Builder webClientBuilder, ObjectMapper mapper) {
    this.properties = properties;
    this.webClient = webClientBuilder.build();
    this.mapper = mapper;
  }

  public LinearIssue createContactIssue(ContactIssueInput input) {
    String labelName = properties.getLabels().getContact();
    return createIssue(
        sanitizeTitle("[Contact] " + StringUtils.defaultIfBlank(input.subject(), "Help request")),
        contactDescription(input),
        labelName,
        "#f2a93b",
        "Contact and help requests submitted from the public BlueChat site.");
  }

  public LinearIssue createFeedbackIssue(FeedbackIssueInput input) {
    String category = StringUtils.defaultIfBlank(input.category(), "general");
    String titleSeed = firstLine(input.feedbackText());
    String labelName = properties.getLabels().getFeedback();
    return createIssue(
        sanitizeTitle("[Feedback/" + category + "] " + titleSeed),
        feedbackDescription(input),
        labelName,
        "#5e6ad2",
        "Improvement requests and user feedback captured by the BlueChat agent.");
  }

  private LinearIssue createIssue(
      String title,
      String description,
      String labelName,
      String labelColor,
      String labelDescription) {
    ensureConfigured();
    JsonNode routing = postGraphql(ROUTING_QUERY, Map.of()).path("data");
    String teamId = resolveTeamId(routing.path("teams").path("nodes"));
    String labelId =
        resolveLabelId(routing.path("issueLabels").path("nodes"), teamId, labelName)
            .orElseGet(() -> createMissingLabel(teamId, labelName, labelColor, labelDescription));

    Map<String, Object> input = new LinkedHashMap<>();
    input.put("teamId", teamId);
    input.put("title", title);
    input.put("description", description);
    input.put("labelIds", List.of(labelId));

    JsonNode response =
        postGraphql(ISSUE_CREATE_MUTATION, Map.of("input", input)).path("data").path("issueCreate");
    if (!response.path("success").asBoolean(false)) {
      throw new LinearIssueException("Linear issueCreate did not succeed");
    }
    LinearIssue issue = toIssue(response.path("issue"));
    log.info("Created Linear issue identifier={} id={}", issue.identifier(), issue.id());
    return issue;
  }

  private void ensureConfigured() {
    if (!properties.isEnabled()) {
      throw new LinearIssueException("Linear issue creation is disabled");
    }
    if (StringUtils.isBlank(properties.getApiKey())) {
      throw new LinearIssueException("Linear API key is not configured");
    }
    if (StringUtils.isBlank(properties.getApiUrl())) {
      throw new LinearIssueException("Linear API URL is not configured");
    }
  }

  private JsonNode postGraphql(String query, Map<String, ?> variables) {
    JsonNode response;
    try {
      response =
          webClient
              .post()
              .uri(properties.getApiUrl().trim())
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
              .bodyValue(Map.of("query", query, "variables", variables))
              .retrieve()
              .bodyToMono(JsonNode.class)
              .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
              .block();
    } catch (LinearIssueException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new LinearIssueException("Linear request failed: " + e.getMessage());
    }
    if (response == null) {
      throw new LinearIssueException("Linear returned an empty response");
    }
    JsonNode errors = response.path("errors");
    if (errors.isArray() && !errors.isEmpty()) {
      throw new LinearIssueException("Linear GraphQL error: " + summarizeErrors(errors));
    }
    return response;
  }

  private String authorizationHeader() {
    return properties.getApiKey().trim();
  }

  private String resolveTeamId(JsonNode teams) {
    String explicitTeamId = StringUtils.trimToNull(properties.getTeamId());
    if (explicitTeamId != null) {
      return explicitTeamId;
    }
    List<JsonNode> teamNodes = new ArrayList<>();
    if (teams.isArray()) {
      teams.forEach(teamNodes::add);
    }
    String teamKey = normalize(properties.getTeamKey());
    String teamName = normalize(properties.getTeamName());
    for (JsonNode team : teamNodes) {
      if (StringUtils.isNotBlank(teamKey) && teamKey.equals(normalize(team.path("key").asText()))) {
        return team.path("id").asText();
      }
      if (StringUtils.isNotBlank(teamName)
          && teamName.equals(normalize(team.path("name").asText()))) {
        return team.path("id").asText();
      }
    }
    if (teamNodes.size() == 1) {
      return teamNodes.get(0).path("id").asText();
    }
    throw new LinearIssueException("Could not resolve Linear team for BlueChat");
  }

  private Optional<String> resolveLabelId(JsonNode labels, String teamId, String labelName) {
    String normalizedLabel = normalize(labelName);
    if (StringUtils.isBlank(normalizedLabel) || !labels.isArray()) {
      return Optional.empty();
    }
    String workspaceLabelId = null;
    for (JsonNode label : labels) {
      if (!normalizedLabel.equals(normalize(label.path("name").asText()))) {
        continue;
      }
      JsonNode team = label.path("team");
      if (team.isObject() && teamId.equals(team.path("id").asText())) {
        return Optional.of(label.path("id").asText());
      }
      if (team.isMissingNode() || team.isNull()) {
        workspaceLabelId = label.path("id").asText();
      }
    }
    return Optional.ofNullable(workspaceLabelId);
  }

  private String createMissingLabel(
      String teamId, String labelName, String labelColor, String labelDescription) {
    if (!properties.isCreateMissingLabels()) {
      throw new LinearIssueException("Linear label is missing: " + labelName);
    }
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("teamId", teamId);
    input.put("name", labelName);
    input.put("color", labelColor);
    input.put("description", labelDescription);
    JsonNode response =
        postGraphql(ISSUE_LABEL_CREATE_MUTATION, Map.of("input", input))
            .path("data")
            .path("issueLabelCreate");
    if (!response.path("success").asBoolean(false)) {
      throw new LinearIssueException("Linear issueLabelCreate did not succeed");
    }
    return response.path("issueLabel").path("id").asText();
  }

  private LinearIssue toIssue(JsonNode issue) {
    return new LinearIssue(
        issue.path("id").asText(),
        StringUtils.trimToNull(issue.path("identifier").asText()),
        issue.path("title").asText(),
        StringUtils.trimToNull(issue.path("url").asText()),
        parseInstant(issue.path("createdAt").asText()));
  }

  private String contactDescription(ContactIssueInput input) {
    return """
        Contact/help request submitted from the public BlueChat site.

        | Field | Value |
        | --- | --- |
        | Submitted at | %s |
        | Account ID | %s |
        | Name | %s |
        | Email | %s |
        | Remote address | %s |
        | User agent | %s |
        | Cap verified | %s |

        ## Subject

        %s

        ## Message

        %s
        """
        .formatted(
            input.submittedAt(),
            nullText(input.accountId()),
            nullText(input.name()),
            nullText(input.email()),
            nullText(input.remoteAddress()),
            nullText(input.userAgent()),
            input.capVerified(),
            nullText(input.subject()),
            nullText(input.message()));
  }

  private String feedbackDescription(FeedbackIssueInput input) {
    return """
        Feedback captured by the BlueChat agent.

        | Field | Value |
        | --- | --- |
        | Submitted at | %s |
        | Account ID | %s |
        | Category | %s |
        | Transport | %s |
        | Sender | %s |
        | Chat GUID | %s |
        | Message GUID | %s |

        ## Feedback

        %s
        """
        .formatted(
            input.submittedAt(),
            nullText(input.accountId()),
            nullText(input.category()),
            nullText(input.transport()),
            nullText(input.sender()),
            nullText(input.chatGuid()),
            nullText(input.messageGuid()),
            nullText(input.feedbackText()));
  }

  private String sanitizeTitle(String title) {
    String sanitized =
        StringUtils.defaultIfBlank(title, "BlueChat issue").replaceAll("\\s+", " ").trim();
    return StringUtils.truncate(sanitized, 255);
  }

  private String firstLine(String text) {
    String trimmed = StringUtils.defaultIfBlank(text, "User feedback").trim();
    int lineBreak = trimmed.indexOf('\n');
    String first = lineBreak >= 0 ? trimmed.substring(0, lineBreak) : trimmed;
    return StringUtils.truncate(first, 120);
  }

  private String nullText(String value) {
    return StringUtils.defaultIfBlank(value, "n/a");
  }

  private String normalize(String value) {
    return StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
  }

  private Instant parseInstant(String value) {
    if (StringUtils.isBlank(value)) {
      return Instant.now();
    }
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException e) {
      return Instant.now();
    }
  }

  private String summarizeErrors(JsonNode errors) {
    List<String> messages = new ArrayList<>();
    for (JsonNode error : errors) {
      String message = StringUtils.trimToNull(error.path("message").asText());
      if (message != null) {
        messages.add(message);
      }
      if (messages.size() == 3) {
        break;
      }
    }
    return messages.isEmpty()
        ? mapper.convertValue(errors, Object.class).toString()
        : String.join("; ", messages);
  }

  public record ContactIssueInput(
      String accountId,
      String name,
      String email,
      String subject,
      String message,
      String remoteAddress,
      String userAgent,
      boolean capVerified,
      Instant submittedAt) {}

  public record FeedbackIssueInput(
      String accountId,
      Instant submittedAt,
      String feedbackText,
      String category,
      String transport,
      String sender,
      String chatGuid,
      String messageGuid) {}

  public record LinearIssue(
      String id, String identifier, String title, String url, Instant createdAt) {
    public String reference() {
      return StringUtils.defaultIfBlank(identifier, id);
    }
  }

  public static class LinearIssueException extends RuntimeException {
    public LinearIssueException(String message) {
      super(message);
    }
  }
}

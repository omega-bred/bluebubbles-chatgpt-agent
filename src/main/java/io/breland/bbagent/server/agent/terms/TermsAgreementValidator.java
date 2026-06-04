package io.breland.bbagent.server.agent.terms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import io.breland.bbagent.server.agent.AgentResponseHelper;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public final class TermsAgreementValidator {
  public static final String DEFAULT_RESPONSES_MODEL = "openai/gpt-4.1-mini";
  private static final double MIN_CONFIDENCE = 0.85;
  private static final String VALIDATOR_PROMPT =
      "You validate whether a user has accepted Terms of Use after being asked to agree. "
          + "Return only compact JSON with fields agreement and confidence. "
          + "agreement must be true only when the message clearly and affirmatively accepts or "
          + "consents to the terms, such as yes, yep, sure, agreed, I accept, sounds good, or "
          + "similar. Do not mark questions, jokes, refusals, uncertainty, conditions, or unrelated "
          + "messages as agreement. confidence must be a number from 0 to 1.";

  private final Supplier<OpenAIClient> openAiSupplier;
  private final ObjectMapper objectMapper;
  private final Supplier<String> responsesModel;

  public TermsAgreementValidator(
      Supplier<OpenAIClient> openAiSupplier,
      ObjectMapper objectMapper,
      Supplier<String> responsesModel) {
    this.openAiSupplier = openAiSupplier;
    this.objectMapper = objectMapper;
    this.responsesModel = responsesModel;
  }

  public boolean isHighConfidenceAgreement(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String cleanModel = StringUtils.defaultIfBlank(responsesModel.get(), DEFAULT_RESPONSES_MODEL);
    ResponseCreateParams params =
        ResponseCreateParams.builder()
            .model(cleanModel)
            .maxOutputTokens(80)
            .temperature(0.0)
            .inputOfResponse(
                List.of(
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.DEVELOPER)
                            .content(VALIDATOR_PROMPT)
                            .build()),
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.USER)
                            .content("User message: " + text.trim())
                            .build())))
            .build();
    try {
      Response response = openAiSupplier.get().responses().create(params);
      TermsAgreementDecision decision =
          parseDecision(AgentResponseHelper.extractResponseText(response));
      boolean accepted = decision.agreement() && decision.confidence() >= MIN_CONFIDENCE;
      log.info(
          "Terms agreement validator result accepted={} confidence={} model={}",
          accepted,
          decision.confidence(),
          cleanModel);
      return accepted;
    } catch (RuntimeException e) {
      log.warn("Terms agreement validation failed", e);
      return false;
    }
  }

  private TermsAgreementDecision parseDecision(String text) {
    if (text == null || text.isBlank()) {
      return new TermsAgreementDecision(false, 0.0);
    }
    String trimmed = text.trim();
    int objectStart = trimmed.indexOf('{');
    int objectEnd = trimmed.lastIndexOf('}');
    if (objectStart >= 0 && objectEnd > objectStart) {
      trimmed = trimmed.substring(objectStart, objectEnd + 1);
    }
    try {
      JsonNode node = objectMapper.readTree(trimmed);
      return new TermsAgreementDecision(
          node.path("agreement").asBoolean(false), node.path("confidence").asDouble(0.0));
    } catch (Exception e) {
      log.warn("Failed to parse terms agreement validator response: {}", text, e);
      return new TermsAgreementDecision(false, 0.0);
    }
  }

  private record TermsAgreementDecision(boolean agreement, double confidence) {}
}

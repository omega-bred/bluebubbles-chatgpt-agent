package io.breland.bbagent.server.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.server.agent.llm.OpenAiClientProvider;
import io.breland.bbagent.server.agent.terms.TermsAgreementValidator;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class TermsDecisionTest {
  private final BackgroundDecisionService decisions = mock(BackgroundDecisionService.class);
  private final OpenAiClientProvider responses = mock(OpenAiClientProvider.class);
  private final TermsAgreementValidator validator =
      new TermsAgreementValidator(
          responses,
          new ObjectMapper(),
          TermsAgreementValidator.DEFAULT_RESPONSES_MODEL,
          decisions);

  @Test
  void deterministicYesDoesNotCallAnyModel() {
    assertThat(validator.isHighConfidenceAgreement("YES!")).isTrue();
    verifyNoInteractions(responses, decisions);
  }

  @Test
  void explicitDecisionRequiresHighProbability() {
    when(decisions.termsEnabled()).thenReturn(true);
    when(decisions.termsAgreement(anyString())).thenReturn(OptionalDouble.of(0.99));
    assertThat(validator.isHighConfidenceAgreement("I unconditionally accept these terms"))
        .isTrue();
    when(decisions.termsAgreement(anyString())).thenReturn(OptionalDouble.of(0.95));
    assertThat(validator.isHighConfidenceAgreement("I guess that is fine")).isFalse();
    verifyNoInteractions(responses);
  }

  @Test
  void refusalOrFailureCannotFallThroughToAnotherConsentClassifier() {
    when(decisions.termsEnabled()).thenReturn(true);
    when(decisions.termsAgreement(anyString())).thenReturn(OptionalDouble.empty());
    assertThat(validator.isHighConfidenceAgreement("Maybe I accept")).isFalse();
    verifyNoInteractions(responses);
  }
}

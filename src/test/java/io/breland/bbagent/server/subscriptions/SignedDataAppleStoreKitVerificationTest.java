package io.breland.bbagent.server.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.apple.itunes.storekit.model.Environment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SignedDataAppleStoreKitVerificationTest {
  @Test
  void parsesEnvironmentIndependentOfDefaultLocale() {
    Locale originalLocale = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      SignedDataAppleStoreKitVerification verification =
          new SignedDataAppleStoreKitVerification(new SubscriptionProperties(), new ObjectMapper());
      Environment environment =
          ReflectionTestUtils.invokeMethod(verification, "environment", "production");

      assertThat(environment).isEqualTo(Environment.PRODUCTION);
    } finally {
      Locale.setDefault(originalLocale);
    }
  }
}

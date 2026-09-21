package io.breland.bbagent.server.agent.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in contract canary; creates only synthetic banks and removes them in finally. */
@EnabledIfEnvironmentVariable(named = "HINDSIGHT_SMOKE_BASE_URL", matches = ".+")
class HindsightLiveIntegTest {
  @Test
  void retainRecallReplaceDeleteAndIsolation() throws Exception {
    String base = System.getenv("HINDSIGHT_SMOKE_BASE_URL");
    String key = Files.readString(Path.of(System.getenv("HINDSIGHT_SMOKE_KEY_FILE"))).trim();
    HindsightClient client = new HindsightClient(true, base, key, Duration.ofSeconds(15), 4096);
    String bank = "bluechat-canary-" + UUID.randomUUID();
    String isolated = bank + "-isolated";
    String doc = UUID.randomUUID().toString();
    String first =
        "The synthetic Cedar project team requires all planning meetings to be held on Tuesdays at 10 AM UTC going forward.";
    String second =
        "The synthetic Cedar project team has permanently moved all planning meetings to Thursdays at 2 PM UTC, replacing the previous Tuesday schedule.";
    try {
      assertThat(client.configureBank(bank, true)).isTrue();
      assertThat(client.configureBank(isolated, false)).isTrue();
      retain(client, bank, doc, first);
      assertThat(client.recall(bank, "When does the Cedar project team hold planning meetings?"))
          .anySatisfy(
              hit -> {
                assertThat(hit.documentId()).isEqualTo(doc);
                assertThat(hit.contentHash()).isEqualTo(DigestUtils.sha256Hex(first));
              });
      assertThat(client.recall(isolated, "Cedar planning meeting schedule")).isEmpty();
      retain(client, bank, doc, second);
      var replaced = client.recall(bank, "Cedar planning meeting schedule");
      assertThat(replaced)
          .isNotEmpty()
          .allSatisfy(
              hit -> assertThat(hit.contentHash()).isEqualTo(DigestUtils.sha256Hex(second)));
      assertThat(client.deleteDocument(bank, doc)).isTrue();
      assertThat(client.recall(bank, "Cedar planning meeting schedule")).isEmpty();
    } finally {
      for (String id : new String[] {bank, isolated}) {
        var request =
            HttpRequest.newBuilder(URI.create(base + "/v1/default/banks/" + id))
                .header("Authorization", "Bearer " + key)
                .DELETE()
                .timeout(Duration.ofSeconds(20))
                .build();
        int status =
            HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding())
                .statusCode();
        assertThat(status).isIn(200, 204, 404);
      }
    }
  }

  private void retain(HindsightClient client, String bank, String doc, String text)
      throws Exception {
    String operation = UUID.randomUUID().toString();
    Instant timestamp = Instant.now();
    assertThat(client.retain(bank, doc, operation, text, DigestUtils.sha256Hex(text), timestamp))
        .isTrue();
    // Same submission simulates retry after a lost acknowledgement.
    assertThat(client.retain(bank, doc, operation, text, DigestUtils.sha256Hex(text), timestamp))
        .isTrue();
    Instant deadline = Instant.now().plusSeconds(180);
    String status;
    do {
      status = client.operationStatus(bank, operation);
      if (status.equals("completed") || status.equals("failed") || status.equals("cancelled"))
        break;
      Thread.sleep(2000);
    } while (Instant.now().isBefore(deadline));
    assertThat(status).isEqualTo("completed");
  }
}

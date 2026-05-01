package io.breland.bbagent.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import io.breland.bbagent.generated.bluebubblesclient.api.V1ContactApi;
import io.breland.bbagent.generated.bluebubblesclient.api.V1MessageApi;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class OauthCallbackSupportTest {
  @Test
  void htmlResponseEscapesTitleAndMessage() {
    OauthCallbackSupport support = new OauthCallbackSupport(new CapturingBBHttpClientWrapper());

    var response = support.htmlResponse(HttpStatus.BAD_REQUEST, "Coder <OAuth>", "Bad & \"wrong\"");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
    assertThat(response.getBody()).contains("<title>Coder &lt;OAuth&gt;</title>");
    assertThat(response.getBody()).contains("Bad &amp; &quot;wrong&quot;");
  }

  @Test
  void sendFollowupSendsSelectedMessageReplyWhenPresent() {
    CapturingBBHttpClientWrapper bbHttpClientWrapper = new CapturingBBHttpClientWrapper();
    OauthCallbackSupport support = new OauthCallbackSupport(bbHttpClientWrapper);

    support.sendFollowup("chat-1", "message-1", "linked");

    ApiV1MessageTextPostRequest request = bbHttpClientWrapper.lastRequest;
    assertThat(request.getChatGuid()).isEqualTo("chat-1");
    assertThat(request.getMessage()).isEqualTo("linked");
    assertThat(request.getSelectedMessageGuid()).isEqualTo("message-1");
    assertThat(request.getPartIndex()).isEqualTo(0);
  }

  @Test
  void sendFollowupSkipsMissingRequiredValues() {
    CapturingBBHttpClientWrapper bbHttpClientWrapper = new CapturingBBHttpClientWrapper();
    OauthCallbackSupport support = new OauthCallbackSupport(bbHttpClientWrapper);

    support.sendFollowup("", "message-1", "linked");
    support.sendFollowup("chat-1", "message-1", " ");

    assertThat(bbHttpClientWrapper.lastRequest).isNull();
  }

  private static final class CapturingBBHttpClientWrapper extends BBHttpClientWrapper {
    private ApiV1MessageTextPostRequest lastRequest;

    private CapturingBBHttpClientWrapper() {
      super("password", (V1MessageApi) null, (V1ContactApi) null);
    }

    @Override
    public void sendTextDirect(ApiV1MessageTextPostRequest request) {
      this.lastRequest = request;
    }
  }
}

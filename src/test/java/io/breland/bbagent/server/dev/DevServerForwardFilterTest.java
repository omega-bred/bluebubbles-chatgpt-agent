package io.breland.bbagent.server.dev;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DevServerForwardFilterTest {

  @Test
  void skipsEntityForRequestsWithoutBody() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/help");

    assertThat(DevServerForwardFilter.hasRequestBody(request)).isFalse();
  }

  @Test
  void detectsChunkedRequestBodyWithoutContentLength() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/help");
    request.addHeader("Transfer-Encoding", "chunked");

    assertThat(DevServerForwardFilter.hasRequestBody(request)).isTrue();
  }

  @Test
  void defaultsMissingContentTypeToBinary() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/help");

    assertThat(DevServerForwardFilter.requestContentType(request))
        .isEqualTo(ContentType.APPLICATION_OCTET_STREAM);
  }

  @Test
  void parsesRequestContentTypeWithCharset() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/help");
    request.setContentType("application/json; charset=UTF-8");

    assertThat(DevServerForwardFilter.requestContentType(request).getMimeType())
        .isEqualTo("application/json");
  }

  @Test
  void skipsHeadersManagedByProxyClient() {
    assertThat(DevServerForwardFilter.shouldForwardRequestHeader("Content-Length")).isFalse();
    assertThat(DevServerForwardFilter.shouldForwardRequestHeader("Transfer-Encoding")).isFalse();
    assertThat(DevServerForwardFilter.shouldForwardRequestHeader("Host")).isFalse();
    assertThat(DevServerForwardFilter.shouldForwardRequestHeader("Accept")).isTrue();
    assertThat(DevServerForwardFilter.shouldForwardRequestHeader("X-Requested-With")).isTrue();
  }
}

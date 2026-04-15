package io.breland.bbagent.server.dev;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.Optional;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntityContainer;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;
import org.springframework.web.servlet.handler.MatchableHandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * Filter used during development to forward unknown requests to the front-end dev server running on
 * port 5174 if it is available.
 */
@Component
@Profile("!prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DevServerForwardFilter extends OncePerRequestFilter {

  public static final int DEV_SERVER_PORT = 5174;
  public static final String DEV_SERVER_HOST = "localhost";
  public static final String DEV_SERVER_URL =
      "http://%s:%d".formatted(DEV_SERVER_HOST, DEV_SERVER_PORT);
  private final CloseableHttpClient httpClient = HttpClients.createDefault();

  @PreDestroy
  public void cleanup() {
    try {
      httpClient.close();
    } catch (Exception ignored) {
    }
  }

  @Autowired private HandlerMappingIntrospector handlerMappingIntrospector;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    boolean isWebsocket =
        Optional.ofNullable(request.getHeader("Upgrade"))
            .map(u -> u.equals("websocket"))
            .orElse(false);

    if (!hasHandler(request) && isDevServerRunning() && !isWebsocket) {
      proxyHttp(request, response);
      return;
    }

    filterChain.doFilter(request, response);
  }

  private void proxyHttp(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String uri =
        DEV_SERVER_URL
            + request.getRequestURI()
            + (request.getQueryString() != null ? "?" + request.getQueryString() : "");

    ClassicHttpRequest proxyReq =
        switch (request.getMethod()) {
          case "POST" -> new HttpPost(uri);
          case "PUT" -> new HttpPut(uri);
          case "PATCH" -> new HttpPatch(uri);
          case "DELETE" -> new HttpDelete(uri);
          default -> new HttpGet(uri);
        };

    Collections.list(request.getHeaderNames())
        .forEach(
            name ->
                Collections.list(request.getHeaders(name))
                    .forEach(value -> proxyReq.addHeader(name, value)));

    if (proxyReq instanceof HttpEntityContainer) {
      ((HttpEntityContainer) proxyReq)
          .setEntity(
              new InputStreamEntity(
                  request.getInputStream(),
                  request.getContentLength(),
                  ContentType.create(request.getContentType())));
    }

    try (CloseableHttpResponse proxied = httpClient.execute(proxyReq)) {
      response.setStatus(proxied.getCode());
      for (Header h : proxied.getHeaders()) {
        response.setHeader(h.getName(), h.getValue());
      }
      if (proxied.getEntity() != null) {
        proxied.getEntity().writeTo(response.getOutputStream());
      }
    }
  }

  private boolean hasHandler(HttpServletRequest request) throws ServletException {
    if (request.getRequestURI().equals("/")) {
      return false;
    }
    try {
      MatchableHandlerMapping mapping =
          handlerMappingIntrospector.getMatchableHandlerMapping(request);
      if (mapping != null) {
        HandlerExecutionChain handler = mapping.getHandler(request);
        if (handler != null && handler.getHandler() instanceof ResourceHttpRequestHandler) {
          return false;
        }
      }
      return mapping != null;
    } catch (IllegalStateException ex) {
      return false;
    } catch (Exception ex) {
      throw new ServletException(ex);
    }
  }

  private boolean isDevServerRunning() {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(DEV_SERVER_HOST, DEV_SERVER_PORT), 100);
      return true;
    } catch (IOException ex) {
      return false;
    }
  }
}

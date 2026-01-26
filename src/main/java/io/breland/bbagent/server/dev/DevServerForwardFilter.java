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
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.core5.http.*;
// import org.apache.http.HttpEntityEnclosingRequest;
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
    // build target URI
    String uri =
        DEV_SERVER_URL
            + request.getRequestURI()
            + (request.getQueryString() != null ? "?" + request.getQueryString() : "");

    // choose the right Apache request
    ClassicHttpRequest proxyReq;
    switch (request.getMethod()) {
      case "GET":
        proxyReq = new HttpGet(uri);
        break;
      case "POST":
        proxyReq = new HttpPost(uri);
        break;
      case "PUT":
        proxyReq = new HttpPut(uri);
        break;
      case "DELETE":
        proxyReq = new HttpDelete(uri);
        break;
      default:
        proxyReq = new HttpGet(uri);
        break;
    }

    // copy headers
    Collections.list(request.getHeaderNames())
        .forEach(
            name ->
                Collections.list(request.getHeaders(name))
                    .forEach(value -> proxyReq.addHeader(name, value)));

    // copy body if needed
    if (proxyReq instanceof HttpEntityContainer) {
      ((HttpEntityContainer) proxyReq)
          .setEntity(
              new InputStreamEntity(
                  request.getInputStream(),
                  request.getContentLength(),
                  ContentType.create(request.getContentType())));
    }

    // execute
    try (CloseableHttpResponse proxied = httpClient.execute(proxyReq)) {
      // status
      response.setStatus(proxied.getCode());
      // headers
      for (Header h : proxied.getHeaders()) {
        response.setHeader(h.getName(), h.getValue());
      }
      // body
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
      // nothing
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

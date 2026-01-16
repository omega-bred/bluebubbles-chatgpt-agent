package io.breland.bbagent.server.controllers;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Slf4j
public class StaticExtensionFilter extends OncePerRequestFilter {

  private static final List<String> EXTENSIONS = List.of(".js", ".css", ".html");

  @Autowired private ResourceLoader resourceLoader;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (!path.contains(".") && path.startsWith("/client/")) {
      log.debug("Checking path {} for extensions", path);
      for (String ext : EXTENSIONS) {
        String potentialPath = "classpath:/static" + path + ext;
        Resource resource = resourceLoader.getResource(potentialPath);
        if (resource.exists()) {
          log.debug("Found static file at {}", potentialPath);
          request.getRequestDispatcher(path + ext).forward(request, response);
          return;
        }
      }
    }
    filterChain.doFilter(request, response);
  }
}

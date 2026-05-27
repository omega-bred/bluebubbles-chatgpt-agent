package io.breland.bbagent.server.agent.cadence;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseOutputItem;
import io.breland.bbagent.server.agent.cadence.models.GeneratedImage;
import java.io.InputStream;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class GeneratedImageExtractor {

  List<GeneratedImage> extract(Response response) {
    if (response == null) {
      return List.of();
    }
    log.debug(
        "Extracting images from response - total of {} items in response",
        response.output().size());
    for (ResponseOutputItem item : response.output()) {
      if (item.imageGenerationCall().isEmpty()) {
        log.debug("Skipping item - not image generation call");
        continue;
      }
      ResponseOutputItem.ImageGenerationCall call = item.imageGenerationCall().get();
      log.info("Got an image generation item {}", call.id());
      if (call.status() != ResponseOutputItem.ImageGenerationCall.Status.COMPLETED) {
        log.warn("Image generation failed(bad status), status was : {}", call.status());
        continue;
      }
      String result = call.result().orElse(null);
      if (result == null || result.isBlank()) {
        log.warn("Image generation failed(blank result): {}", call.id());
        continue;
      }
      byte[] bytes = decodeImageResult(result);
      if (bytes == null || bytes.length == 0) {
        log.warn("Image generation failed(empty bytes): {}", call.id());
        continue;
      }
      String id = call.id();
      String filename = "generated-" + id + ".png";
      log.info("Generated image for {}: {}", call.id(), filename);
      return List.of(new GeneratedImage(bytes, filename));
    }
    return List.of();
  }

  private byte[] decodeImageResult(String result) {
    if (result == null || result.isBlank()) {
      log.warn("Decode failed: empty string");
      return null;
    }
    String trimmed = result.trim();
    if (trimmed.startsWith("data:")) {
      log.debug("Decoding image data(inline)");
      int comma = trimmed.indexOf(',');
      if (comma > 0 && comma < trimmed.length() - 1) {
        return decodeBase64(trimmed.substring(comma + 1));
      }
      return null;
    }
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      log.debug("Need to download image bytes");
      return downloadBytes(trimmed);
    }
    log.debug("Doing b64 decode");
    return decodeBase64(trimmed);
  }

  private byte[] decodeBase64(String value) {
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      log.warn("Failed to decode base64 image result", e);
      return null;
    }
  }

  private byte[] downloadBytes(String url) {
    try (InputStream input = URI.create(url).toURL().openStream()) {
      return input.readAllBytes();
    } catch (Exception e) {
      log.warn("Failed to download image result {}", url, e);
      return null;
    }
  }
}
